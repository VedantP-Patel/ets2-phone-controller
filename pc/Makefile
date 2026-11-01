CXX = g++
CXXFLAGS = -std=c++17 -O3 -Wall -Wextra -Wno-attributes -DWIN32_LEAN_AND_MEAN \
           -I lib/ViGEmClient/include -I lib/ViGEmClient/src -I src
LDFLAGS = lib/ViGEmClient.o -lws2_32 -liphlpapi -lwinmm -lsetupapi -static

SRC = src/main.cpp src/controller.cpp src/protocol.cpp src/filter.cpp src/telemetry_reader.cpp
OUT = ets2-controller.exe

all: $(OUT)

lib/ViGEmClient.o: lib/ViGEmClient/src/ViGEmClient.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(OUT): $(SRC) lib/ViGEmClient.o
	$(CXX) $(CXXFLAGS) -o $(OUT) $(SRC) $(LDFLAGS)

clean:
	del /f $(OUT) 2>nul

.PHONY: all clean
