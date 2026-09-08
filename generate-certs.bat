```bat
@echo off
setlocal EnableDelayedExpansion

REM ============================================================
REM P2P FILE SHARING - SSL CERTIFICATE GENERATOR
REM Windows
REM ============================================================

echo.
echo ============================================
echo  P2P SSL Certificate Generator
echo ============================================
echo.

REM ============================================================
REM CONFIG
REM ============================================================

set "PASSWORD=p2ppassword"

set "CERT_DIR=certs"
set "CA_DIR=%CERT_DIR%\ca"
set "TRACKER_DIR=%CERT_DIR%\tracker"
set "PEER_DIR=%CERT_DIR%\peer"

REM Usage:
REM   generate-certs.bat
REM   generate-certs.bat 192.168.1.100

if "%~1"=="" (
    set "TRACKER_IP=127.0.0.1"
) else (
    set "TRACKER_IP=%~1"
)

echo Tracker IP: %TRACKER_IP%
echo.

REM ============================================================
REM 1. CHECK WINGET
REM ============================================================

echo ============================================
echo  Checking winget
echo ============================================
echo.

where winget >nul 2>&1

if errorlevel 1 (
    echo ERROR: winget is not available.
    echo.
    echo Please install Windows App Installer first.
    echo.
    pause
    exit /b 1
)

echo winget:
winget --version
echo.

REM ============================================================
REM 2. CHECK / INSTALL OPENSSL
REM ============================================================

echo ============================================
echo  Checking OpenSSL
echo ============================================
echo.

where openssl >nul 2>&1

if errorlevel 1 (
    echo OpenSSL not found.
    echo.
    echo Installing OpenSSL Light...
    echo.

    winget install ^
        --id ShiningLight.OpenSSL.Light ^
        --exact ^
        --source winget ^
        --accept-source-agreements ^
        --accept-package-agreements

    if errorlevel 1 (
        echo.
        echo ============================================
        echo ERROR: Failed to install OpenSSL.
        echo ============================================
        echo.
        pause
        exit /b 1
    )

    echo.
    echo OpenSSL installation completed.
    echo Refreshing PATH...
    echo.

    if exist "C:\Program Files\OpenSSL\bin\openssl.exe" (
        set "PATH=C:\Program Files\OpenSSL\bin;!PATH!"
    )

    if exist "C:\Program Files\OpenSSL-Win64\bin\openssl.exe" (
        set "PATH=C:\Program Files\OpenSSL-Win64\bin;!PATH!"
    )

    if exist "C:\Program Files\OpenSSL-Win32\bin\openssl.exe" (
        set "PATH=C:\Program Files\OpenSSL-Win32\bin;!PATH!"
    )
)

where openssl >nul 2>&1

if errorlevel 1 (
    echo.
    echo ERROR: OpenSSL was installed but cannot be found.
    echo.
    echo Please close this CMD window and run the script again.
    echo.
    pause
    exit /b 1
)

echo OpenSSL:
openssl version
echo.

REM ============================================================
REM 3. CHECK / INSTALL JDK
REM ============================================================

echo ============================================
echo  Checking JDK / keytool
echo ============================================
echo.

where keytool >nul 2>&1

if errorlevel 1 (
    echo keytool not found.
    echo.
    echo Installing Microsoft OpenJDK 21...
    echo.

    winget install ^
        --id Microsoft.OpenJDK.21 ^
        --exact ^
        --source winget ^
        --accept-source-agreements ^
        --accept-package-agreements

    if errorlevel 1 (
        echo.
        echo ============================================
        echo ERROR: Failed to install JDK 21.
        echo ============================================
        echo.
        pause
        exit /b 1
    )

    echo.
    echo JDK 21 installation completed.
    echo Refreshing PATH...
    echo.

    for /d %%D in ("C:\Program Files\Microsoft\jdk-*") do (
        if exist "%%~D\bin\keytool.exe" (
            set "PATH=%%~D\bin;!PATH!"
        )
    )

    for /d %%D in ("C:\Program Files\Java\jdk-*") do (
        if exist "%%~D\bin\keytool.exe" (
            set "PATH=%%~D\bin;!PATH!"
        )
    )
)

where keytool >nul 2>&1

if errorlevel 1 (
    echo.
    echo ERROR: JDK was installed but keytool cannot be found.
    echo.
    echo Please close this CMD window and run the script again.
    echo.
    pause
    exit /b 1
)

echo Keytool:
keytool -version
echo.

REM ============================================================
REM 4. REQUIREMENTS OK
REM ============================================================

echo ============================================
echo  Requirements OK
echo ============================================
echo.

echo OpenSSL:
openssl version

echo.
echo Keytool:
keytool -version

echo.

REM ============================================================
REM 5. CLEAN OLD CERTIFICATES
REM ============================================================

echo ============================================
echo  Cleaning old certificates
echo ============================================
echo.

if exist "%CERT_DIR%" (
    echo Removing:
    echo   %CERT_DIR%
    echo.

    rmdir /s /q "%CERT_DIR%"

    if errorlevel 1 (
        echo.
        echo ERROR: Cannot remove old certificate directory.
        echo Make sure files are not being used.
        echo.
        pause
        exit /b 1
    )
)

mkdir "%CA_DIR%"
mkdir "%TRACKER_DIR%"
mkdir "%PEER_DIR%"

if errorlevel 1 goto :error

REM ============================================================
REM 6. ROOT CA
REM ============================================================

echo.
echo ============================================
echo [1/9] Generating Root CA
echo ============================================
echo.

openssl genrsa ^
    -out "%CA_DIR%\ca-key.pem" ^
    4096

if errorlevel 1 goto :error

openssl req -x509 ^
    -new ^
    -sha256 ^
    -days 3650 ^
    -key "%CA_DIR%\ca-key.pem" ^
    -out "%CA_DIR%\ca-cert.pem" ^
    -subj "/C=VN/O=P2P-File-Sharing/CN=P2P Root CA" ^
    -addext "basicConstraints=critical,CA:TRUE,pathlen:1" ^
    -addext "keyUsage=critical,keyCertSign,cRLSign"

if errorlevel 1 goto :error

REM ============================================================
REM 7. INTERMEDIATE CA
REM ============================================================

echo.
echo ============================================
echo [2/9] Generating Tracker Intermediate CA
echo ============================================
echo.

openssl genrsa ^
    -out "%CA_DIR%\tracker-ca-key.pem" ^
    4096

if errorlevel 1 goto :error

openssl req -new ^
    -key "%CA_DIR%\tracker-ca-key.pem" ^
    -out "%CA_DIR%\tracker-ca.csr" ^
    -subj "/C=VN/O=P2P-File-Sharing/CN=P2P Tracker Intermediate CA"

if errorlevel 1 goto :error

(
echo basicConstraints=critical,CA:TRUE,pathlen:0
echo keyUsage=critical,keyCertSign,cRLSign
echo subjectKeyIdentifier=hash
echo authorityKeyIdentifier=keyid,issuer
) > "%CA_DIR%\tracker-ca-ext.cnf"

openssl x509 -req ^
    -in "%CA_DIR%\tracker-ca.csr" ^
    -CA "%CA_DIR%\ca-cert.pem" ^
    -CAkey "%CA_DIR%\ca-key.pem" ^
    -CAcreateserial ^
    -out "%CA_DIR%\tracker-ca-cert.pem" ^
    -days 1825 ^
    -sha256 ^
    -extfile "%CA_DIR%\tracker-ca-ext.cnf"

if errorlevel 1 goto :error

REM ============================================================
REM 8. TRACKER CA KEYSTORE
REM ============================================================

echo.
echo ============================================
echo [3/9] Creating tracker-ca-keystore.jks
echo ============================================
echo.

openssl pkcs12 -export ^
    -inkey "%CA_DIR%\tracker-ca-key.pem" ^
    -in "%CA_DIR%\tracker-ca-cert.pem" ^
    -certfile "%CA_DIR%\ca-cert.pem" ^
    -name tracker-ca ^
    -out "%CA_DIR%\tracker-ca.p12" ^
    -passout pass:%PASSWORD%

if errorlevel 1 goto :error

keytool -importkeystore ^
    -srckeystore "%CA_DIR%\tracker-ca.p12" ^
    -srcstoretype PKCS12 ^
    -srcstorepass %PASSWORD% ^
    -destkeystore "%TRACKER_DIR%\tracker-ca-keystore.jks" ^
    -deststoretype JKS ^
    -deststorepass %PASSWORD% ^
    -alias tracker-ca ^
    -noprompt

if errorlevel 1 goto :error

keytool -import ^
    -trustcacerts ^
    -alias ca ^
    -file "%CA_DIR%\ca-cert.pem" ^
    -keystore "%TRACKER_DIR%\tracker-ca-keystore.jks" ^
    -storepass %PASSWORD% ^
    -noprompt

if errorlevel 1 goto :error

REM ============================================================
REM 9. TRACKER CERTIFICATE
REM ============================================================

echo.
echo ============================================
echo [4/9] Generating Tracker certificate
echo ============================================
echo.

openssl genrsa ^
    -out "%TRACKER_DIR%\tracker-key.pem" ^
    2048

if errorlevel 1 goto :error

openssl req -new ^
    -key "%TRACKER_DIR%\tracker-key.pem" ^
    -out "%TRACKER_DIR%\tracker.csr" ^
    -subj "/C=VN/O=P2P-File-Sharing/CN=tracker"

if errorlevel 1 goto :error

(
echo basicConstraints=critical,CA:FALSE
echo keyUsage=critical,digitalSignature,keyEncipherment
echo extendedKeyUsage=serverAuth,clientAuth
echo subjectAltName=DNS:tracker,IP:127.0.0.1,IP:%TRACKER_IP%
echo subjectKeyIdentifier=hash
echo authorityKeyIdentifier=keyid,issuer
) > "%TRACKER_DIR%\tracker-ext.cnf"

openssl x509 -req ^
    -in "%TRACKER_DIR%\tracker.csr" ^
    -CA "%CA_DIR%\tracker-ca-cert.pem" ^
    -CAkey "%CA_DIR%\tracker-ca-key.pem" ^
    -CAcreateserial ^
    -out "%TRACKER_DIR%\tracker-cert.pem" ^
    -days 825 ^
    -sha256 ^
    -extfile "%TRACKER_DIR%\tracker-ext.cnf"

if errorlevel 1 goto :error

REM ============================================================
REM 10. TRACKER KEYSTORE
REM ============================================================

echo.
echo ============================================
echo [5/9] Creating tracker-keystore.jks
echo ============================================
echo.

openssl pkcs12 -export ^
    -inkey "%TRACKER_DIR%\tracker-key.pem" ^
    -in "%TRACKER_DIR%\tracker-cert.pem" ^
    -certfile "%CA_DIR%\tracker-ca-cert.pem" ^
    -name tracker ^
    -out "%TRACKER_DIR%\tracker-keystore.p12" ^
    -passout pass:%PASSWORD%

if errorlevel 1 goto :error

keytool -importkeystore ^
    -srckeystore "%TRACKER_DIR%\tracker-keystore.p12" ^
    -srcstoretype PKCS12 ^
    -srcstorepass %PASSWORD% ^
    -destkeystore "%TRACKER_DIR%\tracker-keystore.jks" ^
    -deststoretype JKS ^
    -deststorepass %PASSWORD% ^
    -alias tracker ^
    -noprompt

if errorlevel 1 goto :error

REM ============================================================
REM 11. TRACKER TRUSTSTORE
REM ============================================================

echo.
echo ============================================
echo [6/9] Creating tracker-truststore.jks
echo ============================================
echo.

keytool -import ^
    -trustcacerts ^
    -alias ca ^
    -file "%CA_DIR%\ca-cert.pem" ^
    -keystore "%TRACKER_DIR%\tracker-truststore.jks" ^
    -storepass %PASSWORD% ^
    -noprompt

if errorlevel 1 goto :error

REM ============================================================
REM 12. PEER TRUSTSTORE
REM ============================================================

echo.
echo ============================================
echo [7/9] Creating peer-truststore.jks
echo ============================================
echo.

keytool -import ^
    -trustcacerts ^
    -alias ca ^
    -file "%CA_DIR%\ca-cert.pem" ^
    -keystore "%PEER_DIR%\peer-truststore.jks" ^
    -storepass %PASSWORD% ^
    -noprompt

if errorlevel 1 goto :error

REM ============================================================
REM 13. TRACKER .ENV
REM ============================================================

echo.
echo ============================================
echo [8/9] Generating Tracker .env
echo ============================================
echo.

(
echo CA_KEYSTORE=tracker-ca-keystore.jks
echo CA_ALIAS=tracker-ca
echo ROOT_CA_ALIAS=ca
echo.
echo KEYSTORE=tracker-keystore.jks
echo TRUSTSTORE=tracker-truststore.jks
echo KEYSTORE_PASSWORD=%PASSWORD%
echo.
echo TRACKER_ENROLLMENT_PORT=9091
echo SSL_TRACKER_PORT=6001
echo PEER_PORT=5000
echo SOCKET_TIMEOUT_MS=5000
) > "%TRACKER_DIR%\.env"

if errorlevel 1 goto :error

REM ============================================================
REM 14. PEER .ENV + TRUSTSTORE HASH
REM ============================================================

echo.
echo ============================================
echo [9/9] Generating Peer .env
echo ============================================
echo.

set "TRUSTSTORE_HASH="

for /f "tokens=1" %%H in ('certutil -hashfile "%PEER_DIR%\peer-truststore.jks" SHA256 ^| findstr /R /V "hash CertUtil"') do (
    set "TRUSTSTORE_HASH=%%H"
)

set "TRUSTSTORE_HASH=%TRUSTSTORE_HASH: =%"

if "!TRUSTSTORE_HASH!"=="" (
    echo ERROR: Failed to calculate truststore SHA-256.
    goto :error
)

(
echo KEYSTORE_PASSWORD=%PASSWORD%
echo TRUSTSTORE_PASSWORD=%PASSWORD%
echo.
echo KEY_ALIAS=peer
echo.
echo TRACKER_ENROLLMENT_PORT=9091
echo SSL_TRACKER_PORT=6001
echo TRACKER_IP=%TRACKER_IP%
echo.
echo TRUSTSTORE_HASH=%TRUSTSTORE_HASH%
) > "%PEER_DIR%\.env"

if errorlevel 1 goto :error

REM ============================================================
REM 15. CLEANUP
REM ============================================================

echo.
echo Cleaning temporary files...

del /q "%CA_DIR%\tracker-ca.csr" 2>nul
del /q "%CA_DIR%\tracker-ca-ext.cnf" 2>nul
del /q "%CA_DIR%\tracker-ca.p12" 2>nul
del /q "%CA_DIR%\ca-cert.srl" 2>nul
del /q "%CA_DIR%\tracker-ca-cert.srl" 2>nul

del /q "%TRACKER_DIR%\tracker.csr" 2>nul
del /q "%TRACKER_DIR%\tracker-ext.cnf" 2>nul
del /q "%TRACKER_DIR%\tracker-keystore.p12" 2>nul

REM ============================================================
REM 16. RESULT
REM ============================================================

echo.
echo.
echo ============================================
echo  Generated successfully
echo ============================================
echo.

echo Tracker:
echo   %TRACKER_DIR%\tracker-ca-keystore.jks
echo   %TRACKER_DIR%\tracker-keystore.jks
echo   %TRACKER_DIR%\tracker-truststore.jks
echo   %TRACKER_DIR%\.env
echo.

echo Peer:
echo   %PEER_DIR%\peer-truststore.jks
echo   %PEER_DIR%\.env
echo.

echo Password:
echo   %PASSWORD%
echo.

echo Tracker IP:
echo   %TRACKER_IP%
echo.

echo Peer Truststore SHA-256:
echo   %TRUSTSTORE_HASH%
echo.

echo ============================================
echo  Certificate hierarchy
echo ============================================
echo.
echo   Root CA
echo      ^|
echo      +-- Tracker Intermediate CA
echo              ^|
echo              +-- Tracker Certificate
echo              ^|
echo              +-- Peer Certificates
echo.

echo Peer will generate peer-keystore.jks automatically
echo when it enrolls with Tracker.
echo.

echo ============================================
echo  DONE
echo ============================================
echo.

pause
exit /b 0

REM ============================================================
REM ERROR
REM ============================================================

:error

echo.
echo.
echo ============================================
echo  ERROR: Certificate generation failed
echo ============================================
echo.
echo Check the error message above.
echo.

pause
exit /b 1