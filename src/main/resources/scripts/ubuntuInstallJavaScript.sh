#!/usr/bin/env bash

set -ex

# Install Java
sudo apt-get -y update
sudo apt-get install openjdk-17-jdk

java -version
