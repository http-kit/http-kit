#!/bin/bash

echo "Will generate keystore"
echo "For password use: 123456"
echo "For distinguished name use: localhost:9898"
echo
keytool -genkeypair -alias jetty -keyalg RSA -keysize 2048 -keystore ssl_keystore -validity 36500
