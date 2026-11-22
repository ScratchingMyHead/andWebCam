BINARY := camrelay
PREFIX ?= /usr/local

.PHONY: all build clean install

all: build

build:
	go build -o $(BINARY) .

clean:
	rm -f $(BINARY)

install: build
	install -m 0755 $(BINARY) $(PREFIX)/bin/camrelay
