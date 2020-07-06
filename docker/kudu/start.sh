#!/usr/bin/env bash

# Set your machine's ip here. e.g 192.168.1.x
export KUDU_QUICKSTART_IP=
sudo iptables -D INPUT -p tcp --dport 7000:9000 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 7000:9000 -j ACCEPT
docker-compose -f quickstart.yml up -d
# docker exec -it $(docker ps -aqf "name=kudu-master-1") /bin/bash
