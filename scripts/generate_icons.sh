#!/bin/bash
# Script to generate Android app icons from assets/raccoon_logo.png

SOURCE_IMG="assets/raccoon_logo.png"

if [ ! -f "$SOURCE_IMG" ]; then
    echo "Error: Source image $SOURCE_IMG not found."
    exit 1
fi

# Define sizes
declare -A SIZES
SIZES=( ["mdpi"]=48 ["hdpi"]=72 ["xhdpi"]=96 ["xxhdpi"]=144 ["xxxhdpi"]=192 )

for density in "${!SIZES[@]}"; do
    size=${SIZES[$density]}
    target_dir="res/mipmap-$density"
    target_file="$target_dir/ic_launcher.png"
    
    mkdir -p "$target_dir"
    
    echo "Generating $density icon (${size}x${size})..."
    convert "$SOURCE_IMG" -resize ${size}x${size} "$target_file"
done

echo "Icon generation complete."
