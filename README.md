# Pencroff
A meta-indexer for blockchains

Named after Bonadventure Pencroff from https://en.wikipedia.org/wiki/The_Mysterious_Island.


# Development quickstart instructions

1. Add scala project at `<root>/ingestor` to IntelliJ. The usual steps of importing a project apply.
1. Goto docker/kudu and edit the two shell scripts. Set your machine's local ip (The one assigned by your router) there. `start.sh` opens ports in the firewall that are required for database start up using `iptables`. Replace these commands with your favorite FW manager.
1. Run `start.sh` to start kudu.
1. Open `<root>/ingestor/src/main/resources/reference.conf` in an editor. Set the `host`, `port` and `protocol` config items to point to a tezos node of your choice. Edit nothing else.
1. In IntelliJ, run `BlockIngestor.scala`. It should automatically create and start ingesting data from the node you specified in the previous step.
1. After a while in IntelliJ, run `DataServer.scala`. This runs an HTTP server that mimics the node's api. Note that all urls to this have a prefix to them, currently hard coded to `tezos`.
1. Open postman, run a query the following queries `localhost:8080/tezos/chains/main/blocks/0` and `localhost:8080/tezos/chains/main/blocks/BLockGenesisGenesisGenesisGenesisGenesisd6f5afWyME7`. You should see identical output from these. Compare to the output from your configured tezos node. If everything matches you are good to start developing.
