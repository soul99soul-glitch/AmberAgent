cmake_minimum_required(VERSION 3.16)
project(amber_mosh_dependencies LANGUAGES CXX)
include(CheckSymbolExists)
include(CheckIncludeFileCXX)
check_symbol_exists(gettimeofday "sys/time.h" HAVE_GETTIMEOFDAY)
check_symbol_exists(clock_gettime "time.h" HAVE_CLOCK_GETTIME)
check_symbol_exists(posix_memalign "stdlib.h" HAVE_POSIX_MEMALIGN)
check_symbol_exists(IP_MTU_DISCOVER "netinet/in.h" HAVE_IP_MTU_DISCOVER)
check_symbol_exists(IP_RECVTOS "netinet/in.h" HAVE_IP_RECVTOS)
check_symbol_exists(be64toh "endian.h" HAVE_DECL_BE64TOH)
check_symbol_exists(betoh64 "endian.h" HAVE_DECL_BETOH64)
check_include_file_cxx(sys/uio.h HAVE_SYS_UIO_H)
check_include_file_cxx(strings.h HAVE_STRINGS_H)
check_include_file_cxx(endian.h HAVE_ENDIAN_H)
if(NOT HAVE_CLOCK_GETTIME)
  message(FATAL_ERROR "Mosh requires monotonic clock_gettime")
endif()
configure_file(config.h.in "${CMAKE_CURRENT_BINARY_DIR}/include/config.h")
set(SRC "${CMAKE_CURRENT_SOURCE_DIR}/build/sources/mosh-1.4.0/src")
set(PROTO_OUT "${CMAKE_CURRENT_BINARY_DIR}/protobufs")
file(MAKE_DIRECTORY "${PROTO_OUT}")
foreach(name userinput hostinput transportinstruction)
  add_custom_command(OUTPUT "${PROTO_OUT}/${name}.pb.cc" "${PROTO_OUT}/${name}.pb.h"
    COMMAND "${AMBER_PROTOC}" "--cpp_out=${PROTO_OUT}" -I "${SRC}/protobufs" "${SRC}/protobufs/${name}.proto"
    DEPENDS "${SRC}/protobufs/${name}.proto" VERBATIM)
  list(APPEND PROTO_SOURCES "${PROTO_OUT}/${name}.pb.cc")
endforeach()
add_library(amber_mosh STATIC ${PROTO_SOURCES}
  ${SRC}/crypto/base64.cc ${SRC}/crypto/crypto.cc ${SRC}/crypto/ocb_internal.cc
  ${SRC}/network/network.cc ${SRC}/network/transportfragment.cc ${SRC}/network/compressor.cc
  ${SRC}/statesync/completeterminal.cc ${SRC}/statesync/user.cc
  ${SRC}/terminal/parseraction.cc ${SRC}/terminal/parser.cc ${SRC}/terminal/parserstate.cc
  ${SRC}/terminal/terminal.cc ${SRC}/terminal/terminaldispatcher.cc ${SRC}/terminal/terminaldisplay.cc
  ${SRC}/terminal/terminaldisplayinit.cc ${SRC}/terminal/terminalframebuffer.cc
  ${SRC}/terminal/terminalfunctions.cc ${SRC}/terminal/terminaluserinput.cc ${SRC}/util/timestamp.cc)
target_compile_features(amber_mosh PUBLIC cxx_std_17)
set_target_properties(amber_mosh PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(amber_mosh PUBLIC AMBER_MOSH_EMBEDDED=1)
target_include_directories(amber_mosh PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/include" "${PROTO_OUT}"
  "${AMBER_DEP_INSTALL}/include" "${AMBER_CRYPTO_INSTALL}/include"
  ${SRC}/crypto ${SRC}/network ${SRC}/statesync ${SRC}/terminal ${SRC}/util)
install(TARGETS amber_mosh ARCHIVE DESTINATION lib)
install(DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/include/" "${PROTO_OUT}/" DESTINATION include FILES_MATCHING PATTERN "*.h")
foreach(module crypto network statesync terminal util)
  install(DIRECTORY "${SRC}/${module}/" DESTINATION "include/mosh/${module}" FILES_MATCHING PATTERN "*.h")
endforeach()
