#include "ssh_core.h"
#include <fcntl.h>
#include <unistd.h>
#include <future>
#include <iostream>
#include <thread>
#include <chrono>
#include <atomic>
#ifdef __APPLE__
#include <mach/mach.h>
#endif
namespace amber::ssh {
void SetResolverDelayForTests(int milliseconds);
int ResolverTasksForTests();
}
using namespace amber::ssh;
using namespace std::chrono_literals;
void Require(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
int Fds() { int count = 0; for (int i = 0; i < 1024; ++i) if (fcntl(i, F_GETFD) >= 0) ++count; return count; }
int Threads() {
#ifdef __APPLE__
    thread_act_array_t list; mach_msg_type_number_t count;
    Require(task_threads(mach_task_self(), &list, &count) == KERN_SUCCESS, "task_threads failed");
    for (unsigned i = 0; i < count; ++i) mach_port_deallocate(mach_task_self(), list[i]);
    vm_deallocate(mach_task_self(), reinterpret_cast<vm_address_t>(list), count * sizeof(thread_t));
    return static_cast<int>(count);
#else
    return 0;
#endif
}
Result Probe(Runtime &runtime, const std::string &id, const std::string &host, int port) {
    std::promise<Result> promise; auto future = promise.get_future();
    runtime.Probe(id, {host, port, 5000}, [&](Result result) { promise.set_value(std::move(result)); });
    Require(future.wait_for(6s) == std::future_status::ready, "probe stuck"); return future.get();
}
int main(int argc, char **argv) {
    try {
        const int port = argc > 1 ? std::stoi(argv[1]) : 22224;
        const std::string pin = argc > 2 ? argv[2] : "";
        Runtime runtime;
        auto warm = Probe(runtime, "warm", "127.0.0.1", port); Require(!warm.error, "real probe failed");
        std::this_thread::sleep_for(20ms);
        int initialFds = Fds(), initialThreads = Threads();
        for (int i = 0; i < 50; ++i) {
            auto result = Probe(runtime, "repeat-" + std::to_string(i), "127.0.0.1", port);
            Require(!result.error, "repeated real handshake failed");
        }
        std::this_thread::sleep_for(50ms);
        Require(runtime.OwnerCount() == 0 && runtime.HandleCount() == 0, "probe owners/handles remain");
        Require(Fds() == initialFds, "probe fd leak"); Require(Threads() == initialThreads, "probe thread leak");
        std::cout << "PASS fifty real numeric probes; fd=" << Fds() << " threads=" << Threads() << " owners=0 handles=0\n";
        if (!pin.empty()) {
            ConnectionOptions connection; connection.host = "127.0.0.1"; connection.port = port;
            connection.username = std::getenv("AMBER_FIXTURE_USERNAME"); connection.secret = std::getenv("AMBER_FIXTURE_PASSWORD");
            connection.fingerprint = pin; connection.authMethod = "password";
            StartOptions start; start.kind = "exec"; start.command = "printf resource-ok";
            for (int i = 0; i < 20; ++i) {
                std::promise<Result> promise; auto future = promise.get_future();
                runtime.Start("exec-" + std::to_string(i), connection, start, [&](Result result) { promise.set_value(std::move(result)); });
                Require(future.wait_for(6s) == std::future_status::ready, "start stuck"); auto result = future.get(); Require(!result.error, "exec failed to start");
                auto deadline = std::chrono::steady_clock::now() + 6s; Packet packet;
                do { packet = runtime.Read(result.handle, 3); std::this_thread::sleep_for(1ms); } while(packet.state == "running" && std::chrono::steady_clock::now() < deadline);
                Require(packet.state == "exited" && packet.exitCode == 0, "exec did not exit successfully");
                std::promise<Result> closed; auto closeFuture = closed.get_future();
                runtime.Close(result.handle, "release", [&](Result item) { closed.set_value(std::move(item)); });
                Require(closeFuture.wait_for(2s) == std::future_status::ready && !closeFuture.get().error, "release failed");
            }
            std::this_thread::sleep_for(50ms);
            Require(runtime.OwnerCount() == 0 && runtime.HandleCount() == 0, "exec owners/handles remain");
            Require(Fds() == initialFds, "exec fd leak"); Require(Threads() == initialThreads, "exec thread leak");
            std::cout << "PASS twenty authenticated exec+release; fd=" << Fds() << " threads=" << Threads() << " owners=0 handles=0\n";
        }
        SetResolverDelayForTests(500);
        std::promise<Result> delayed; auto ready = delayed.get_future();
        auto before = std::chrono::steady_clock::now();
        runtime.Probe("dns-cancel", {"localhost", port, 5000}, [&](Result result) { delayed.set_value(std::move(result)); });
        while (!ResolverTasksForTests()) std::this_thread::sleep_for(1ms);
        runtime.Cancel("dns-cancel");
        Require(ready.wait_for(200ms) == std::future_status::ready, "DNS cancellation held owner");
        auto result = ready.get(); Require(result.error && result.error->code == "cancelled", "DNS cancel result incorrect");
        std::this_thread::sleep_for(20ms);
        Require(runtime.OwnerCount() == 0 && Fds() == initialFds, "DNS cancelled owner or fd remains");
        Require(ResolverTasksForTests() == 1, "resolver fixture did not delay");
        Require(std::chrono::steady_clock::now() - before < 300ms, "DNS cancel slow");
        std::this_thread::sleep_for(550ms);
        Require(ResolverTasksForTests() == 0 && Threads() == initialThreads, "late resolver not reclaimed");
        std::cout << "PASS DNS delayed completion: cancelled owner/fd within 200ms; independent resolver reclaimed after return\n";
        auto invalid = Probe(runtime, "", "127.0.0.1", port); Require(invalid.error && invalid.error->code == "invalid_arguments", "invalid ID not rejected");
        runtime.Shutdown();
        std::cout << "PASS shutdown and invalid request after lifetime stress\n";
    } catch (const std::exception &error) { std::cerr << error.what() << '\n'; return 1; }
}
