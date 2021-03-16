# Pencroff
A forever cache/meta-indexer for blockchains.

# What is Pencroff?
In order to run a block indexer, multiple queries need to be run against a blockchain node in order to pull all of the necessary data needed. However, most blockchain nodes are currently a single-threaded application which makes it less ideal for fast, rapid query performance. 

Pencroff is the solution! It syncs off of supported blockchain nodes, and stores necessary endpoint data to replicate the node's functionality. It uses a hash map and multi-threaded performance to make syncing block indexers much faster than a regular node!

# Running Pencroff
### System Requirements
Pencroff is officially supported on Ubuntu, but should also work with other *nix systems.

In order to run effectively, your machine should have:
- 8GB RAM
- A sizeable (preferably solid state) disk
- 4 Processor Cores

### Prerequisistes
The following need to be installed and configured properly so Pencroff works:
- `sbt`
- `Docker`
- `docker-compose`
- `iptables`

## Starting Pencroff
Pencroff consists of two processes, the `BlockIngestor` which reads stores queries block by block, and the `DataServer` which responds to HTTP queries. 

To start these processes:
1. Clone the project to your machine.
1. Go to `docker/kudu` and edit the two shell scripts. Set your machine's local ip (The one assigned by your router) there. `start.sh` opens ports in the firewall that are required for database start up using `iptables`. Replace these commands with your favorite FW manager if so required.
1. Run `start.sh` to start kudu.
1. Open `<root>/ingestor/src/main/resources/reference.conf` in an editor. Set the `host`, `port` and `protocol` config items to point to a tezos node of your choice. Edit nothing else.
1. `cd` into `<root>/ingestor`  and run `sbt runMain tech.cryptonomic.BlockIngestor.scala`. It should automatically create the necessary tables and start ingesting data from the node you specified in the previous step.
1. After some blocks have been ingested, run `sbt runMain tech.cryptonomic.DataServer.scala`. This runs a HTTP server that mimics the node's api. Note that all urls to this have a prefix to them, currently hard coded to `tezos`.
1. Open postman, run the following queries `localhost:8080/tezos/chains/main/blocks/0` and `localhost:8080/tezos/chains/main/blocks/BLockGenesisGenesisGenesisGenesisGenesisd6f5afWyME7`. You should see identical output from these. Compare to the output from your configured tezos node. If everything matches you are good to start developing.

See `ingestor/src/main/resources/tezos.json` for the model that the Ingestor is using to fetch data. This can be edited to add different API calls to be replicated.

## Configuring Conseil to work with Pencroff
Pencroff is configured out of the box to work with Cryptonomic's own block indexer and query tool, [Conseil](https://github.com/Cryptonomic/Conseil). This means that it includes all of the queries that Conseil needs to fully synchronize its database.

In order to make Conseil work, follow the instructions [here](https://github.com/Cryptonomic/Conseil/wiki/Building-Conseil) to build it, and then [here](https://github.com/Cryptonomic/Conseil/wiki/Configuring-Conseil-(latest-release)) to properly configure it.

In the `platforms` section of the config file, change the `path-prefix` value to `tezos/`. This will add the URL prefix mentioned above to Conseil to allow it to properly access API Endpoints.

Instead of pointing Conseil to a Tezos Node, point it to the running instance of Pencroff by changing the `protocol` , `hostname`, and `port` configuration details.
# Early Load Test Results

No suitable Tz node was available to test against. Pencroff results follow:

Notes:
1. All tests peformed locally.
1. Each simulated user runs 100 queries, each fetching a random block between 0 and 350.
1. After each fetch, the simulated user waits for 25 milliseconds before querying again.
1. Kudu cluster is running dockerized inside a virtual machine.
1. VM has 8 gigs, 8 virtual CPU cores, running ArchLinux, kernel 5.7.6.
1. X axis on response time graphs is milliseconds.
1. No errors whatsoever were reported during any of the tests.

### TLDR Summary
1. Sub second response across all tests
1. Average RPS on 500 User test is 1470 Request / Second. *This, naively and very incorrectly, gives us an upper bound of around 127 mil requests / day*.

### 5 Users / 100 Queries each
![Response time distribution](docs/loadtest/rt-5U-100Q.png)
![Requests per second](docs/loadtest/rps-5U-100Q.png)

### 50 Users / 100 Queries each
![Response time distribution](docs/loadtest/rt-50U-100Q.png)
![Requests per second](docs/loadtest/rps-50U-100Q.png)

### 500 Users / 100 Queries each
![Response time distribution](docs/loadtest/rt-500U-100Q.png)
![Requests per second](docs/loadtest/rps-500U-100Q.png)
