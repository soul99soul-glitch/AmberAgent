#include "stream_frame_rate.h"
#include <native_vsync/native_vsync.h>
#define LOG_DOMAIN 0xD003900
#define LOG_TAG "StreamingFrameRate"
#include <hilog/log.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace {
using SetRange = int (*)(OH_NativeVSync *, OH_NativeVSync_ExpectedRateRange *);

// A receiver may have copied its callback before Destroy(). Keep the tiny gate
// alive for the process; callbacks never access the receiver or the NAPI env.
struct CallbackGate {
    std::mutex mutex;
    std::condition_variable ready;
    uint64_t sequence = 0;
};
CallbackGate &Gate() { static auto *gate = new CallbackGate; return *gate; }
void OnFrame(long long, void *data)
{
    auto &gate = *static_cast<CallbackGate *>(data);
    std::lock_guard<std::mutex> lock(gate.mutex);
    ++gate.sequence;
    gate.ready.notify_one();
}

class StreamingFrameRate {
public:
    int Start()
    {
        if (running_) return 0;
        Stop();
        // API20 is looked up rather than linked, preserving API12 loadability.
        auto setRange = reinterpret_cast<SetRange>(dlsym(RTLD_DEFAULT,
            "OH_NativeVSync_SetExpectedFrameRateRange"));
        if (!setRange) return -1;
        const char name[] = "AmberStreaming120";
        auto *vsync = OH_NativeVSync_Create(name, sizeof(name) - 1);
        if (!vsync) return -2;
        OH_NativeVSync_ExpectedRateRange range {60, 120, 120};
        int result = setRange(vsync, &range);
        if (result != 0) { OH_NativeVSync_Destroy(vsync); return result; }
        running_ = true;
        try {
            worker_ = std::thread([this, vsync] { Run(vsync); });
        } catch (...) {
            running_ = false;
            OH_NativeVSync_Destroy(vsync);
            return -3;
        }
        OH_LOG_INFO(LOG_APP, "range accepted60/120/120");
        return 0;
    }

    void Stop()
    {
        // Serialize the predicate change with wait(), preventing a lost wakeup.
        { std::lock_guard<std::mutex> lock(Gate().mutex); running_ = false; }
        Gate().ready.notify_one();
        if (worker_.joinable()) worker_.join();
    }

private:
    void Run(OH_NativeVSync *vsync)
    {
        auto &gate = Gate();
        long long lastPeriod = 0;
        uint64_t frames = 0;
        auto sampledAt = std::chrono::steady_clock::now();
        while (running_) {
            uint64_t previous;
            { std::lock_guard<std::mutex> lock(gate.mutex); previous = gate.sequence; }
            int result = OH_NativeVSync_RequestFrame(vsync, OnFrame, &gate);
            if (result != 0) {
                OH_LOG_WARN(LOG_APP, "RequestFrame failed %{public}d", result);
                break;
            }
            std::unique_lock<std::mutex> lock(gate.mutex);
            bool received = gate.ready.wait_for(lock, std::chrono::milliseconds(100),
                [&] { return !running_ || gate.sequence != previous; });
            lock.unlock();
            if (!running_) break;
            if (!received) { OH_LOG_WARN(LOG_APP, "VSync callback timed out"); break; }
            ++frames;
            long long period = 0;
            if (OH_NativeVSync_GetPeriod(vsync, &period) == 0 && period != lastPeriod) {
                lastPeriod = period;
                OH_LOG_INFO(LOG_APP, "periodNs=%{public}lld", period);
            }
            auto now = std::chrono::steady_clock::now();
            double elapsed = std::chrono::duration<double>(now - sampledAt).count();
            if (elapsed >= 5.0) {
                OH_LOG_INFO(LOG_APP, "callbackHz=%{public}.1f", frames / elapsed);
                frames = 0; sampledAt = now;
            }
        }
        OH_NativeVSync_Destroy(vsync);
        running_ = false;
        OH_LOG_INFO(LOG_APP, "stopped and receiver destroyed");
    }
    std::atomic<bool> running_ {false};
    std::thread worker_;
};

StreamingFrameRate &Controller() { static auto *controller = new StreamingFrameRate; return *controller; }
void Cleanup(void *) { Controller().Stop(); }
napi_value SetActive(napi_env env, napi_callback_info info)
{
    size_t count = 1;
    napi_value argument = nullptr;
    bool active = false;
    if (napi_get_cb_info(env, info, &count, &argument, nullptr, nullptr) != napi_ok || count != 1 ||
        napi_get_value_bool(env, argument, &active) != napi_ok) {
        napi_throw_type_error(env, nullptr, "Expected a boolean"); return nullptr;
    }
    int status = 0;
    if (active) status = Controller().Start();
    else Controller().Stop();
    napi_value result = nullptr;
    napi_create_int32(env, status, &result);
    return result;
}
} // namespace

napi_value RegisterStreamingFrameRate(napi_env env, napi_value exports)
{
    napi_property_descriptor property {"setStreamingFrameRateActive", nullptr, SetActive,
        nullptr, nullptr, nullptr, napi_default, nullptr};
    if (napi_define_properties(env, exports, 1, &property) != napi_ok ||
        napi_add_env_cleanup_hook(env, Cleanup, nullptr) != napi_ok) return nullptr;
    return exports;
}
