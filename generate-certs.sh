#!/bin/bash

set -e

# ============================================================
# CONFIG
# ============================================================

PASSWORD="p2ppassword"

CERT_DIR="./certs"

CA_DIR="$CERT_DIR/ca"
TRACKER_DIR="$CERT_DIR/tracker"
PEER_DIR="$CERT_DIR/peer"

TRACKER_IP="${1:-127.0.0.1}"

rm -rf "$CERT_DIR"

mkdir -p "$CA_DIR"
mkdir -p "$TRACKER_DIR"
mkdir -p "$PEER_DIR"

echo "============================================"
echo " P2P SSL Certificate Generator"
echo "============================================"
echo "Tracker IP: $TRACKER_IP"
echo ""

# ============================================================
# 1. ROOT CA
# ============================================================

echo "[1/8] Generating Root CA..."

openssl genrsa \
    -out "$CA_DIR/ca-key.pem" \
    4096

openssl req -x509 \
    -new \
    -sha256 \
    -days 3650 \
    -key "$CA_DIR/ca-key.pem" \
    -out "$CA_DIR/ca-cert.pem" \
    -subj "/C=VN/O=P2P-File-Sharing/CN=P2P Root CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:1" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

# ============================================================
# 2. INTERMEDIATE CA
#    This is the CA used by Tracker to sign Peer certificates
# ============================================================

echo "[2/8] Generating Intermediate CA..."

openssl genrsa \
    -out "$CA_DIR/tracker-ca-key.pem" \
    4096

openssl req -new \
    -key "$CA_DIR/tracker-ca-key.pem" \
    -out "$CA_DIR/tracker-ca.csr" \
    -subj "/C=VN/O=P2P-File-Sharing/CN=P2P Tracker Intermediate CA"

cat > "$CA_DIR/tracker-ca-ext.cnf" <<EOF
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF

openssl x509 -req \
    -in "$CA_DIR/tracker-ca.csr" \
    -CA "$CA_DIR/ca-cert.pem" \
    -CAkey "$CA_DIR/ca-key.pem" \
    -CAcreateserial \
    -out "$CA_DIR/tracker-ca-cert.pem" \
    -days 1825 \
    -sha256 \
    -extfile "$CA_DIR/tracker-ca-ext.cnf"

# ============================================================
# 3. Create tracker-ca-keystore.jks
#
# Config:
# CA_KEYSTORE = tracker-ca-keystore.jks
# CA_ALIAS = tracker-ca
# ROOT_CA_ALIAS = ca
#
# ============================================================

echo "[3/8] Creating tracker-ca-keystore.jks..."

openssl pkcs12 -export \
    -inkey "$CA_DIR/tracker-ca-key.pem" \
    -in "$CA_DIR/tracker-ca-cert.pem" \
    -certfile "$CA_DIR/ca-cert.pem" \
    -name tracker-ca \
    -out "$CA_DIR/tracker-ca.p12" \
    -passout "pass:$PASSWORD"

keytool -importkeystore \
    -srckeystore "$CA_DIR/tracker-ca.p12" \
    -srcstoretype PKCS12 \
    -srcstorepass "$PASSWORD" \
    -destkeystore "$TRACKER_DIR/tracker-ca-keystore.jks" \
    -deststoretype JKS \
    -deststorepass "$PASSWORD" \
    -alias tracker-ca \
    -noprompt

# Add Root CA as alias "ca"
keytool -import \
    -trustcacerts \
    -alias ca \
    -file "$CA_DIR/ca-cert.pem" \
    -keystore "$TRACKER_DIR/tracker-ca-keystore.jks" \
    -storepass "$PASSWORD" \
    -noprompt

# ============================================================
# 4. TRACKER CERTIFICATE
# ============================================================

echo "[4/8] Generating Tracker certificate..."

openssl genrsa \
    -out "$TRACKER_DIR/tracker-key.pem" \
    2048

openssl req -new \
    -key "$TRACKER_DIR/tracker-key.pem" \
    -out "$TRACKER_DIR/tracker.csr" \
    -subj "/C=VN/O=P2P-File-Sharing/CN=tracker"

cat > "$TRACKER_DIR/tracker-ext.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:tracker,IP:127.0.0.1,IP:$TRACKER_IP
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF

openssl x509 -req \
    -in "$TRACKER_DIR/tracker.csr" \
    -CA "$CA_DIR/tracker-ca-cert.pem" \
    -CAkey "$CA_DIR/tracker-ca-key.pem" \
    -CAcreateserial \
    -out "$TRACKER_DIR/tracker-cert.pem" \
    -days 825 \
    -sha256 \
    -extfile "$TRACKER_DIR/tracker-ext.cnf"

# ============================================================
# 5. tracker-keystore.jks
# ============================================================

echo "[5/8] Creating tracker-keystore.jks..."

cat "$TRACKER_DIR/tracker-cert.pem" \
    "$CA_DIR/tracker-ca-cert.pem" \
    "$CA_DIR/ca-cert.pem" \
    > "$TRACKER_DIR/tracker-chain.pem"

openssl pkcs12 -export \
    -inkey "$TRACKER_DIR/tracker-key.pem" \
    -in "$TRACKER_DIR/tracker-cert.pem" \
    -certfile "$CA_DIR/tracker-ca-cert.pem" \
    -name tracker \
    -out "$TRACKER_DIR/tracker-keystore.p12" \
    -passout "pass:$PASSWORD"

keytool -importkeystore \
    -srckeystore "$TRACKER_DIR/tracker-keystore.p12" \
    -srcstoretype PKCS12 \
    -srcstorepass "$PASSWORD" \
    -destkeystore "$TRACKER_DIR/tracker-keystore.jks" \
    -deststoretype JKS \
    -deststorepass "$PASSWORD" \
    -alias tracker \
    -noprompt

# ============================================================
# 6. tracker-truststore.jks
# ============================================================

echo "[6/8] Creating tracker-truststore.jks..."

keytool -import \
    -trustcacerts \
    -alias ca \
    -file "$CA_DIR/ca-cert.pem" \
    -keystore "$TRACKER_DIR/tracker-truststore.jks" \
    -storepass "$PASSWORD" \
    -noprompt

# ============================================================
# 7. peer-truststore.jks
#
# This is pre-bundled into Peer.
# Peer uses it BEFORE it has a certificate to verify Tracker.
# ============================================================

echo "[7/8] Creating peer-truststore.jks..."

keytool -import \
    -trustcacerts \
    -alias ca \
    -file "$CA_DIR/ca-cert.pem" \
    -keystore "$PEER_DIR/peer-truststore.jks" \
    -storepass "$PASSWORD" \
    -noprompt

# ============================================================
# 8. Cleanup
# ============================================================

echo "[8/8] Cleaning temporary files..."

rm -f \
    "$CA_DIR/tracker-ca.csr" \
    "$CA_DIR/tracker-ca-ext.cnf" \
    "$CA_DIR/tracker-ca.p12" \
    "$CA_DIR/ca-cert.srl" \
    "$CA_DIR/tracker-ca-cert.srl" \
    "$TRACKER_DIR/tracker.csr" \
    "$TRACKER_DIR/tracker-ext.cnf" \
    "$TRACKER_DIR/tracker-keystore.p12"

echo ""
echo "============================================"
echo " Generated successfully"
echo "============================================"
echo ""

echo "Tracker:"
echo "  tracker-ca-keystore.jks"
echo "  tracker-keystore.jks"
echo "  tracker-truststore.jks"
echo ""

echo "Peer:"
echo "  peer-truststore.jks"
echo ""

echo "Password:"
echo "  $PASSWORD"
echo ""

echo "============================================"
echo " Truststore SHA-256"
echo "============================================"

sha256sum "$PEER_DIR/peer-truststore.jks"

echo ""
echo "Copy this hash to Peer SSLUtils.TRUSTSTORE_HASH"
echo ""

echo "============================================"
# ============================================================
# Generate .env files
# ============================================================

echo ""
echo "Generating .env files..."

cat > "$TRACKER_DIR/.env" <<EOF
# Tracker SSL / Certificate

CA_KEYSTORE=tracker-ca-keystore.jks
CA_ALIAS=tracker-ca
ROOT_CA_ALIAS=ca

KEYSTORE=tracker-keystore.jks
TRUSTSTORE=tracker-truststore.jks

KEYSTORE_PASSWORD=$PASSWORD

# Tracker Network

TRACKER_ENROLLMENT_PORT=9091
SSL_TRACKER_PORT=6001
PEER_PORT=5000
SOCKET_TIMEOUT_MS=5000
EOF

TRUSTSTORE_HASH=$(sha256sum "$PEER_DIR/peer-truststore.jks" | awk '{print $1}')

cat > "$PEER_DIR/.env" <<EOF
# Peer SSL / Certificate

KEYSTORE_PASSWORD=$PASSWORD
TRUSTSTORE_PASSWORD=$PASSWORD

KEY_ALIAS=peer

# Tracker Connection

TRACKER_ENROLLMENT_PORT=9091
SSL_TRACKER_PORT=6001
TRACKER_IP=$TRACKER_IP

# Truststore integrity

TRUSTSTORE_HASH=$TRUSTSTORE_HASH
EOF

echo "Generated:"
echo "  $TRACKER_DIR/.env"
echo "  $PEER_DIR/.env"
