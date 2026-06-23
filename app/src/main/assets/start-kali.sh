#!/data/data/com.kalinrx/files/usr/bin/bash
# Ankali Auto-start Script
# This script automatically extracts and starts Kali Linux environment

KALI_ROOT="/data/data/com.kalinrx/files/kali-arm64"
KALI_TAR="/data/data/com.kalinrx/files/usr/share/kali-arm64.tar.xz"
PROOT_BIN="/data/data/com.kalinrx/files/usr/bin/proot"
LOADER_BIN="/data/data/com.kalinrx/files/usr/libexec/proot/loader"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}[+] Initializing KaliNRX environment...${NC}"

# Extract Kali rootfs if not exists
if [ ! -d "$KALI_ROOT" ]; then
    echo -e "${YELLOW}[*] Extracting Kali rootfs (this may take a few minutes)...${NC}"
    mkdir -p "$KALI_ROOT"
    tar -xJf "$KALI_TAR" -C "$KALI_ROOT"
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}[+] Kali rootfs extracted successfully${NC}"
    else
        echo -e "${RED}[-] Failed to extract Kali rootfs${NC}"
        exit 1
    fi
fi

# Set up environment
export KALI_DIR="$KALI_ROOT"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

# Mount necessary filesystems
mount_proc() {
    if [ ! -d "$KALI_ROOT/proc" ]; then
        mkdir -p "$KALI_ROOT/proc"
    fi
}

mount_sys() {
    if [ ! -d "$KALI_ROOT/sys" ]; then
        mkdir -p "$KALI_ROOT/sys"
    fi
}

mount_dev() {
    if [ ! -d "$KALI_ROOT/dev" ]; then
        mkdir -p "$KALI_ROOT/dev"
    fi
}

# Start Kali environment with proot
start_kali() {
    echo -e "${GREEN}[+] Starting Kali Linux...${NC}"
    
    # Check if kali-run exists
    if [ -x "$KALI_ROOT/.kali-config/kali-run" ]; then
        exec "$PROOT_BIN" \
            --link2symlink \
            -0 \
            -r "$KALI_ROOT" \
            -b /dev \
            -b /proc \
            -b /sys \
            -w /root \
            /bin/env -i \
            HOME=/root \
            TERM=$TERM \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            LANG=en_US.UTF-8 \
            /bin/bash --login -c "/.kali-config/kali-run"
    else
        echo -e "${RED}[-] kali-run not found, starting basic shell...${NC}"
        exec "$PROOT_BIN" \
            --link2symlink \
            -0 \
            -r "$KALI_ROOT" \
            -b /dev \
            -b /proc \
            -b /sys \
            -w /root \
            /bin/env -i \
            HOME=/root \
            TERM=$TERM \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            LANG=en_US.UTF-8 \
            /bin/bash --login
    fi
}

# Main execution
mount_proc
mount_sys
mount_dev
start_kali
