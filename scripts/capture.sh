#!/bin/bash
# UI screenshot capture helper
export LD_LIBRARY_PATH=/home/z/my-project/.libs/extracted/usr/lib/x86_64-linux-gnu
export LIBGL_ALWAYS_SOFTWARE=1
export DISPLAY=:99
MODE="$1"        # empty = landing
NAME="${2:-landing}"
pkill -f "target/release/preview" 2>/dev/null
pkill -f "Xvfb :99" 2>/dev/null
sleep 0.5
(Xvfb :99 -screen 0 412x915x24 +extension GLX 2>/dev/null &)
sleep 1
if [ -n "$MODE" ]; then
  ZAI_OPEN="$MODE" /home/z/my-project/zai-repo/target/release/preview 2>/dev/null &
else
  /home/z/my-project/zai-repo/target/release/preview 2>/dev/null &
fi
APP=$!
sleep 8
ffmpeg -y -loglevel error -f x11grab -video_size 412x915 -i :99 -frames:v 1 "/home/z/my-project/download/app_$NAME.png"
kill $APP 2>/dev/null
pkill -f "target/release/preview" 2>/dev/null
exit 0
