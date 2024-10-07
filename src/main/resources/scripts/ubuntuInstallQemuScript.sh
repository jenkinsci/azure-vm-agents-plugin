#!/usr/bin/env bash

# Install QEMU

set -ex

sudo DEBIAN_FRONTEND=noninteractive apt-get install -y qemu-user-static

"qemu-$(uname -m)-static" --version
