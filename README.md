# ring-adapter

A fast, asynchronous web server in pure java nio.

I write it for the web server of [Rssminer](http://rssminer.net).

# Design goal

* Intended to be used as a library
* Simple, single interface: response = handle(request)
* Fast, I like fast. Should handle 200k+ concurrent connection, and
  40k+ req/s if `hello world` response.
* Memory efficient.

## Usage
