#!/usr/bin/env bash
# InstaLogger Build Script

set -e

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DEST="$HOME/.instalogger/bin"
WEB_BUILD_DIR="$HOME/.instalogger_web_build"

echo "Building InstaLogger..."

# Frontend build
if [ ! -d "$BASE_DIR/web/dist" ]; then
    echo "Building frontend..."
    mkdir -p "$WEB_BUILD_DIR"
    cp -r "$BASE_DIR/web/"* "$WEB_BUILD_DIR/" 2>/dev/null || true
    cd "$WEB_BUILD_DIR"
    if [ ! -d "node_modules" ]; then
        npm install --no-bin-links --silent
    fi
    if [ -f "node_modules/@esbuild/android-arm64/bin/esbuild" ]; then
        chmod +x "node_modules/@esbuild/android-arm64/bin/esbuild" 2>/dev/null || true
    fi
    node ./node_modules/vite/bin/vite.js build --logLevel warn
    mkdir -p "$BASE_DIR/web/dist"
    cp -r dist/* "$BASE_DIR/web/dist/"
fi

# Backend build
mkdir -p "$BIN_DEST"
if [ ! -f "$BIN_DEST/instalogger_backend" ]; then
    echo "Building backend..."
    cd "$BASE_DIR/backend"
    if [ -n "$PREFIX" ]; then
        TARGET_DIR="$HOME/.cargo_targets/instalogger"
        mkdir -p "$TARGET_DIR"
        CARGO_TARGET_DIR="$TARGET_DIR" cargo build --release
        cp "$TARGET_DIR/release/instalogger_backend" "$BIN_DEST/instalogger_backend"
    else
        cargo build --release
        cp target/release/instalogger_backend "$BIN_DEST/instalogger_backend"
    fi
    chmod +x "$BIN_DEST/instalogger_backend"
fi

# Global command setup for Termux
if [ -n "$PREFIX" ] && [ -d "$PREFIX/bin" ]; then
    cat << EOF > "$PREFIX/bin/instalogger"
#!/usr/bin/env bash
exec bash "$BASE_DIR/instalogger" "\$@"
EOF
    chmod +x "$PREFIX/bin/instalogger"
fi

echo "Build complete."
