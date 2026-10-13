"""
strip_fmv0_headers.py

Removes the first 32 bytes from every .fmv0 file in a folder you choose,
and saves the fixed files into a new "output" subfolder.

HOW TO USE (Windows):
1. Double-click this file to run it (requires Python installed:
   https://www.python.org/downloads/  - during install, check "Add Python to PATH").
   OR open a Command Prompt in this folder and run:  python strip_fmv0_headers.py
2. When prompted, paste/type the full path to the folder containing your .fmv0 files.
3. The script will create an "output" folder inside that folder and write the
   fixed files there, with the header bytes removed.
"""

import os
import sys

HEADER_BYTES_TO_STRIP = 32


def strip_header(input_path, output_path, num_bytes=HEADER_BYTES_TO_STRIP):
    with open(input_path, "rb") as f:
        data = f.read()

    if len(data) <= num_bytes:
        print(f"  ! Skipped (file too small, only {len(data)} bytes): {os.path.basename(input_path)}")
        return False

    with open(output_path, "wb") as f:
        f.write(data[num_bytes:])
    return True


def main():
    print("=== FMV0 Header Stripper ===")
    print(f"This will remove the first {HEADER_BYTES_TO_STRIP} bytes from every .fmv0 file.\n")

    folder = input("Enter the full path to the folder with your .fmv0 files: ").strip().strip('"')

    if not os.path.isdir(folder):
        print(f"\nError: '{folder}' is not a valid folder. Exiting.")
        input("\nPress Enter to close...")
        sys.exit(1)

    output_folder = os.path.join(folder, "output")
    os.makedirs(output_folder, exist_ok=True)

    fmv0_files = [f for f in os.listdir(folder) if f.lower().endswith(".fmv0")]

    if not fmv0_files:
        print(f"\nNo .fmv0 files found in: {folder}")
        input("\nPress Enter to close...")
        sys.exit(0)

    print(f"\nFound {len(fmv0_files)} .fmv0 file(s). Processing...\n")

    success_count = 0
    for filename in fmv0_files:
        input_path = os.path.join(folder, filename)
        output_path = os.path.join(output_folder, filename)
        print(f"Processing: {filename}")
        if strip_header(input_path, output_path):
            success_count += 1

    print(f"\nDone! {success_count}/{len(fmv0_files)} file(s) processed successfully.")
    print(f"Output folder: {output_folder}")
    input("\nPress Enter to close...")


if __name__ == "__main__":
    main()
