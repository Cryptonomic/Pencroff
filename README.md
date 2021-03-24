# Pencroff
A forever cache/meta-indexer for blockchains.

_Note: This is experimental and work in progress and should not be deployed to production without understanding what it is._

# What is Pencroff?
In order to run a block indexer, multiple queries need to be run against a blockchain node in order to pull all of the necessary data needed. However, most blockchain nodes are currently a single-threaded application which makes it less ideal for fast, rapid query performance. 

Pencroff is the solution! It syncs off of supported blockchain nodes, and stores necessary endpoint data to replicate the node's functionality. It uses a hash map and multi-threaded performance to make syncing block indexers much faster than a regular node!

Check out the [wiki](https://github.com/Cryptonomic/Pencroff/wiki) for more information on the project, and how to run and use it!

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
