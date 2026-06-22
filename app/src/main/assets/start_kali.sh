#!/system/bin/sh
# AnKali startup script

# Get the app data directory
DATA_DIR="/data/data/com.kalinrx/files"

# Check if kali-arm64 directory exists
if [ ! -d "$DATA_DIR/kali-arm64" ]; then
    echo "Kali environment not found. Extracting..."
    
    # Check if the compressed file exists
    if [ -f "$DATA_DIR/kali-arm64.tar.xz" ]; then
        cd "$DATA_DIR"
        mkdir -p kali-arm64
        cd kali-arm64
        tar -xf ../kali-arm64.tar.xz
        rm -f ../kali-arm64.tar.xz
    else
        echo "Error: kali-arm64.tar.xz not found!"
        echo "Please download the Kali image first."
        exit 1
    fi
fi

# Set up environment variables
export KALI_DIR="$DATA_DIR/kali-arm64"
export PATH="$KALI_DIR/usr/bin:$PATH"

# Run the kali-run script to initialize the environment
if [ -f "$KALI_DIR/.kali-config/kali-run" ]; then
    exec "$KALI_DIR/.kali-config/kali-run"
else
    echo "Error: kali-run not found!"
    exit 1
fi
