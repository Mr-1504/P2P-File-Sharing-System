@echo off
echo Generating SSL certificates for P2P File Sharing System with 2-layer CA...

REM Create certificates directory if it doesn't exist
if not exist "certificates" mkdir certificates

echo Creating keystore for Root CA (Offline)...
keytool -genkeypair -keyalg RSA -keysize 2048 -keystore certificates/ca-keystore.jks -alias ca -dname "CN=P2P-Root-CA, OU=P2P, O=P2P File Sharing, L=Local, ST=State, C=US" -keypass p2ppassword -storepass p2ppassword -validity 3650 -ext bc:c

echo Exporting Root CA certificate...
keytool -exportcert -keystore certificates/ca-keystore.jks -alias ca -file certificates/ca-cert.cer -storepass p2ppassword -rfc

echo Creating keystore for Intermediate CA (Online)...
keytool -genkeypair -keyalg RSA -keysize 2048 -keystore certificates/tracker-ca-keystore.jks -alias tracker-ca -dname "CN=P2P-Intermediate-CA, OU=Tracker Server, O=P2P File Sharing, L=Local, ST=State, C=US" -keypass p2ppassword -storepass p2ppassword -validity 3650 -ext bc:c

echo Creating certificate signing request for Intermediate CA...
keytool -certreq -keystore certificates/tracker-ca-keystore.jks -alias tracker-ca -file certificates/tracker-ca-cert.csr -storepass p2ppassword

echo Signing Intermediate CA certificate with Root CA...
keytool -gencert -keystore certificates/ca-keystore.jks -alias ca -infile certificates/tracker-ca-cert.csr -outfile certificates/tracker-ca-cert-signed.cer -storepass p2ppassword -keypass p2ppassword -validity 3650 -ext san=dns:tracker.p2p.local,ip:127.0.0.1 -ext bc:c

echo Exporting Intermediate CA certificate...
keytool -exportcert -keystore certificates/tracker-ca-keystore.jks -alias tracker-ca -file certificates/tracker-ca-cert.cer -storepass p2ppassword -rfc

echo Importing Root CA certificate into Intermediate CA keystore...
keytool -importcert -keystore certificates/tracker-ca-keystore.jks -file certificates/ca-cert.cer -alias ca -storepass p2ppassword -noprompt

echo Importing signed Intermediate CA certificate...
keytool -importcert -keystore certificates/tracker-ca-keystore.jks -file certificates/tracker-ca-cert-signed.cer -alias tracker-ca -storepass p2ppassword -keypass p2ppassword -noprompt

echo Creating keystore for Tracker server...
keytool -genkeypair -keyalg RSA -keysize 2048 -keystore certificates/tracker-keystore.jks -alias tracker -dname "CN=tracker.p2p.local, OU=Tracker Server, O=P2P File Sharing, L=Local, ST=State, C=US" -keypass p2ppassword -storepass p2ppassword -validity 3650

echo Creating certificate signing request for Tracker...
keytool -certreq -keystore certificates/tracker-keystore.jks -alias tracker -file certificates/tracker-cert.csr -storepass p2ppassword

echo Signing Tracker certificate with Intermediate CA...
keytool -gencert -keystore certificates/tracker-ca-keystore.jks -alias tracker-ca -infile certificates/tracker-cert.csr -outfile certificates/tracker-cert-signed.cer -storepass p2ppassword -keypass p2ppassword -validity 3650 -ext san=dns:tracker.p2p.local,ip:127.0.0.1,ip:127.0.0.1

echo Importing Root CA and Intermediate CA into Tracker truststore...
keytool -importcert -keystore certificates/tracker-truststore.jks -alias ca -file certificates/ca-cert.cer -storepass p2ppassword -noprompt
keytool -importcert -keystore certificates/tracker-truststore.jks -alias tracker-ca -file certificates/tracker-ca-cert-signed.cer -storepass p2ppassword -noprompt

echo Importing Intermediate CA certificate into Tracker keystore...
keytool -importcert -keystore certificates/tracker-keystore.jks -file certificates/tracker-ca-cert-signed.cer -alias tracker-ca -storepass p2ppassword -noprompt

echo Importing signed Tracker certificate...
keytool -importcert -keystore certificates/tracker-keystore.jks -file certificates/tracker-cert-signed.cer -alias tracker -storepass p2ppassword -keypass p2ppassword

REM Generate Peer truststore with Root and Intermediate CA certs
echo Creating Peer truststore...
keytool -importcert -keystore certificates/peer-truststore.jks -alias ca -file certificates/ca-cert.cer -storepass p2ppassword -noprompt
keytool -importcert -keystore certificates/peer-truststore.jks -alias tracker-ca -file certificates/tracker-ca-cert-signed.cer -storepass p2ppassword -noprompt

echo SSL certificates generated successfully!
echo.
echo Summary of generated files in 'certificates' directory:
echo - ca-keystore.jks: Root Certificate Authority keystore (store offline!)
echo - ca-cert.cer: Root CA certificate
echo - tracker-ca-keystore.jks: Intermediate CA keystore (place on Tracker server)
echo - tracker-ca-cert.cer: Intermediate CA certificate
echo - tracker-ca-cert-signed.cer: Intermediate CA cert signed by Root CA
echo - tracker-keystore.jks: Tracker server keystore
echo - tracker-truststore.jks: Tracker truststore (contains Root and Intermediate CA)
echo - peer-truststore.jks: Peer truststore (contains Root and Intermediate CA)
echo.
echo Default password for all keystores: p2ppassword
echo.
echo IMPORTANT: Keep ca-keystore.jks offline and secure!
echo Place tracker-ca-keystore.jks on the Tracker server for certificate signing.

pause
