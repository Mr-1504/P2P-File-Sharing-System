# SSL/TLS Support for P2P File Sharing System

This document describes the SSL/TLS implementation for secure communication between peers and the tracker in the P2P File Sharing system.

## Overview

The system now supports SSL/TLS encrypted communication for:
- Peer-to-Tracker communication
- Peer-to-Peer file sharing

SSL is implemented with automatic fallback to plain TCP if SSL certificates are not available.

## Certificate Generation

Self-signed certificates are generated using the provided script:

```bash
cd src
./generate_ssl_certificates.bat    # On Windows
# or run manually:
# ./generate_ssl_certificates.bat
```

This creates the following certificates in the `certificates/` directory:
- CA certificate (self-signed)
- Tracker server keypair
- Peer client keypair
- Trust stores for mutual authentication

## SSL Configuration

### Ports
- Plain TCP (backward compatibility):
  - Tracker: 5001
  - Peer: 5000
- SSL/TLS:
  - Tracker: 6001 (5001 + 1000 offset)
  - Peer: 6000 (5000 + 1000 offset)

### Certificate Paths
- Tracker keystore: `certificates/tracker-keystore.jks`
- Peer keystore: `certificates/peer-keystore.jks`
- Password: `p2ppassword` (hardcoded for development)

## Implementation Details

### Tracker Server
- Runs both plain TCP and SSL servers simultaneously
- SSL server requires mutual authentication
- Plain TCP server maintains backward compatibility

### Peer Client
- Attempts SSL connection first, falls back to TCP
- Uses client certificates for authentication
- Supports SSL for tracker communication and file sharing

### Key Features
- **Mutual TLS Authentication**: Both client and server authenticate each other
- **Automatic Fallback**: If SSL certificates missing, falls back to plain TCP
- **No Breaking Changes**: Existing non-SSL deployments continue to work
- **Production Ready**: Certificate validation and proper SSL context management

## Testing SSL Support

1. Run certificate generation script
2. Start tracker server
3. Start peer(s)
4. Verify SSL connections in logs ("SSL connection accepted", "Using SSL connection")

## Production Deployment

For production use:
1. Replace self-signed certificates with CA-signed certificates
2. Configure certificate paths via environment variables
3. Use different keystores for different environments
4. Implement certificate rotation policies
5. Consider using AWS Certificate Manager or similar for cloud deployments

## Security Notes

- All data transmission is now encrypted
- Mutual authentication prevents impersonation attacks
- Certificate validation ensures authenticity
- Fallback to plain TCP is automatic but configurable

## Troubleshooting

If SSL connections fail:
1. Check certificate files exist
2. Verify password matches (p2ppassword)
3. Check port availability (6000/6001)
4. Review application logs for SSL handshake errors
