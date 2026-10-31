#!/bin/bash
# UI screenshot: Xvfb üzərində desktop preview + x11grab
# İstifadə: shot.sh <ad> [ZAI_TEST_SEND=...]
export LD_LIBRARY_PATH=/home/z/my-project/.libs/extracted/usr/lib/x86_64-linux-gnu
export LIBGL_ALWAYS_SOFTWARE=1
export DISPLAY=:99
NAME="$1"
shift || true
BIN=/home/z/my-project/zai-repo/target/release/zai-mobile
pkill -f "target/release/zai-mobile" 2>/dev/null
pkill -f "Xvfb :99" 2>/dev/null
sleep 0.5
(Xvfb :99 -screen 0 412x915x24 +extension GLX 2>/dev/null &)
sleep 1
env "$@" ZAI_TIMING=1 "$BIN" >/tmp/zai-preview.log 2>&1 &
APP=$!
sleep 9
ffmpeg -y -loglevel error -draw_mouse 0 -f x11grab -video_size 412x915 -i :99 -frames:v 1 "/home/z/my-project/download/app_$NAME.png"
kill $APP 2>/dev/null
pkill -f "target/release/zai-mobile" 2>/dev/null
exit 0
