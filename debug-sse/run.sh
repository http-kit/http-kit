#!/bin/bash

cd ..
lein install
cd debug-sse

echo "Running lein"
lein run &
sleep 3

echo
echo "Running curl"
echo "*** KILL THIS MANUALLY ***"
echo
curl localhost:8080 -vv

echo
echo "Done"
