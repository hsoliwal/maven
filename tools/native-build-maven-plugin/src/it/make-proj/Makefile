# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# A hand-written makefile: static library, program, check and install (DESTDIR/PREFIX), as many projects have.
CFLAGS ?= -O2
PREFIX ?= /usr/local
ifeq ($(OS),Windows_NT)
EXE = .exe
endif

all: fxtool$(EXE)

libfxcore.a: src/core.o
	$(AR) rcs $@ src/core.o

fxtool$(EXE): src/main.o libfxcore.a
	$(CC) -o $@ src/main.o libfxcore.a

src/%.o: src/%.c include/fx.h
	$(CC) $(CFLAGS) -Iinclude -DFX_BUILD=1 -c $< -o $@

check: all
	./fxtool 2 3 5
	if ./fxtool 2 2 5; then exit 1; fi

install: all
	mkdir -p $(DESTDIR)$(PREFIX)/bin $(DESTDIR)$(PREFIX)/lib $(DESTDIR)$(PREFIX)/include
	cp fxtool$(EXE) $(DESTDIR)$(PREFIX)/bin/
	cp libfxcore.a $(DESTDIR)$(PREFIX)/lib/
	cp include/fx.h $(DESTDIR)$(PREFIX)/include/

clean:
	rm -f src/*.o libfxcore.a fxtool$(EXE)

.PHONY: all check install clean
