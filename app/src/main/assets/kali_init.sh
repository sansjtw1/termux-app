#!/system/bin/sh
# AnKali startup initialization script

DATA_DIR="/data/data/com.kalinrx/files"
KALI_DIR="$DATA_DIR/kali-arm64"

# Create kali directory if not exists
mkdir -p "$KALI_DIR"

# Extract .kali-config from assets if not exists
if [ ! -d "$KALI_DIR/.kali-config" ]; then
    echo "[AnKali] Setting up Kali environment..."
    
    # Copy configuration files
    for file in .kali-config kali.c start_kali.sh; do
        if [ -f "/data/data/com.kalinrx/files/assets/$file" ]; then
            cp -r "/data/data/com.kalinrx/files/assets/$file" "$DATA_DIR/" 2>/dev/null || true
        fi
    done
fi

# Check if proot exists
if [ ! -f "$KALI_DIR/bin/proot" ]; then
    mkdir -p "$KALI_DIR/bin"
    # Copy proot from assets if available
fi

# Run the kali configuration
if [ -f "$KALI_DIR/.kali-config/kali_conf" ]; then
    echo "[AnKali] Running Kali configuration..."
fi

# Start bash shell with Kali environment
exec sh -c "cd $KALI_DIR && export HOME=/root && export TERM=xterm-256color && exec bash"
