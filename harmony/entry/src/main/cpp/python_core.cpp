#include "python_core.h"
#include <Python.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace amber::python {
namespace {
constexpr size_t OUTPUT_LIMIT = 128 * 1024;
struct Control {
    std::atomic<bool> cancelled {false};
    std::chrono::steady_clock::time_point deadline;
    bool timedOut = false;
    bool outputLimit = false;
    std::string stdoutText;
    std::string stderrText;
    bool Stopped() {
        if (!timedOut && std::chrono::steady_clock::now() >= deadline) timedOut = true;
        return cancelled || timedOut || outputLimit;
    }
};
thread_local Control *current = nullptr;
int Poll() {
    if (current && current->Stopped()) {
        PyErr_SetString(PyExc_KeyboardInterrupt, "Embedded Python execution interrupted");
        return -1;
    }
    return 0;
}
int Trace(PyObject *, PyFrameObject *frame, int, PyObject *) {
    if (!current || !current->Stopped()) return 0;
    // Keep tracing through object/module destruction. Only interrupt user code
    // (or a stdlib call beneath it), never CPython/helper finalization itself.
    PyFrameObject *cursor = frame; Py_INCREF(cursor);
    bool userCode = false;
    while (cursor) {
        PyCodeObject *code = PyFrame_GetCode(cursor);
        if (code) {
            userCode = PyUnicode_CompareWithASCIIString(code->co_filename, "<embedded-python>") == 0;
            Py_DECREF(code);
        }
        PyFrameObject *parent = userCode ? nullptr : PyFrame_GetBack(cursor);
        Py_DECREF(cursor); cursor = parent;
        if (userCode) break;
    }
    return userCode ? Poll() : 0;
}
PyObject *Checkpoint(PyObject *, PyObject *) {
    if (Poll() < 0) return nullptr;
    Py_RETURN_NONE;
}
PyObject *Stopped(PyObject *, PyObject *) {
    return PyBool_FromLong(current && current->Stopped());
}
PyObject *EnableTrace(PyObject *, PyObject *) { PyEval_SetTrace(Trace, nullptr); Py_RETURN_NONE; }
PyObject *Write(PyObject *, PyObject *args) {
    PyObject *value = nullptr; int isStderr = 0;
    if (!PyArg_ParseTuple(args, "Up:write", &value, &isStderr)) return nullptr;
    PyObject *bytes = PyUnicode_AsEncodedString(value, "utf-8", "backslashreplace");
    if (!bytes) return nullptr;
    if (current) {
        size_t length = static_cast<size_t>(PyBytes_GET_SIZE(bytes));
        const char *data = PyBytes_AS_STRING(bytes);
        size_t used = current->stdoutText.size() + current->stderrText.size();
        size_t keep = std::min(length, OUTPUT_LIMIT - used);
        if (keep < length) {
            current->outputLimit = true;
            while (keep && (static_cast<unsigned char>(data[keep]) & 0xC0) == 0x80) --keep;
        }
        (isStderr ? current->stderrText : current->stdoutText).append(data, keep);
    }
    Py_DECREF(bytes);
    return PyLong_FromSsize_t(PyUnicode_GET_LENGTH(value));
}
PyObject *Unraisable(PyObject *, PyObject *args) {
    // GC may run after sys.stderr is cleared. Keep finalizer diagnostics in the
    // bounded execution result, never the process stderr/hilog fallback.
    if (!current || current->Stopped()) Py_RETURN_NONE;
    PyObject *error = PyObject_GetAttrString(args, "exc_value");
    PyObject *text = error ? PyObject_Str(error) : nullptr;
    Py_XDECREF(error);
    if (!text) { PyErr_Clear(); Py_RETURN_NONE; }
    PyObject *line = PyUnicode_FromFormat("Exception ignored in Python finalizer: %U\n", text);
    Py_DECREF(text);
    PyObject *arguments = line ? PyTuple_Pack(2, line, Py_True) : nullptr;
    Py_XDECREF(line);
    PyObject *written = arguments ? Write(nullptr, arguments) : nullptr;
    Py_XDECREF(arguments); Py_XDECREF(written);
    if (PyErr_Occurred()) PyErr_Clear();
    Py_RETURN_NONE;
}
PyMethodDef methods[] = {
    {"checkpoint", Checkpoint, METH_NOARGS, nullptr}, {"stopped", Stopped, METH_NOARGS, nullptr},
    {"enable_trace", EnableTrace, METH_NOARGS, nullptr},
    {"write", Write, METH_VARARGS, nullptr}, {"unraisable", Unraisable, METH_O, nullptr},
    {nullptr, nullptr, 0, nullptr}
};
PyModuleDef controlModule = {PyModuleDef_HEAD_INIT, "_amber_control", nullptr, -1, methods,
    nullptr, nullptr, nullptr, nullptr};
PyObject *InitControl() { return PyModule_Create(&controlModule); }
struct Client {
    std::mutex mutex;
    std::condition_variable settled;
    bool closed = false;
    std::unordered_map<std::string, std::shared_ptr<Control>> controls;
};
struct Request {
    std::string id;
    Options options;
    std::shared_ptr<Client> client;
    std::shared_ptr<Control> control;
    Complete complete;
};
class Owner {
public:
    Owner() : thread_([this] { Loop(); }) {}
    ~Owner() {
        { std::lock_guard<std::mutex> lock(mutex_); stopping_ = true; }
        ready_.notify_all(); thread_.join();
    }
    void Submit(Request request) {
        { std::lock_guard<std::mutex> lock(mutex_); queue_.push_back(std::move(request)); }
        ready_.notify_one();
    }
private:
    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<Request> queue_;
    bool stopping_ = false;
    std::thread thread_;
    PyThreadState *main_ = nullptr;
    std::string resourceRoot_;
    bool Initialize(const std::string &root) {
        if (main_) return resourceRoot_ == root;
        static std::once_flag builtins;
        std::call_once(builtins, [] { PyImport_AppendInittab("_amber_control", InitControl); });
        PyPreConfig pre; PyPreConfig_InitIsolatedConfig(&pre); pre.utf8_mode = 1;
        PyStatus status = Py_PreInitialize(&pre);
        if (PyStatus_Exception(status)) return false;
        PyConfig config; PyConfig_InitIsolatedConfig(&config);
        config.install_signal_handlers = 0; config.site_import = 0; config.write_bytecode = 0;
        config.parse_argv = 0; config.module_search_paths_set = 1;
        config.pathconfig_warnings = 0; config.use_environment = 0;
        PyConfig_SetBytesString(&config, &config.program_name, "AmberEmbeddedPython");
        PyConfig_SetBytesString(&config, &config.home, root.c_str());
        PyConfig_SetBytesString(&config, &config.filesystem_encoding, "utf-8");
        PyConfig_SetBytesString(&config, &config.filesystem_errors, "surrogateescape");
        PyConfig_SetBytesString(&config, &config.stdio_encoding, "utf-8");
        std::string zip = root + "/python314.zip";
        wchar_t *zipWide = Py_DecodeLocale(zip.c_str(), nullptr);
        wchar_t *rootWide = Py_DecodeLocale(root.c_str(), nullptr);
        if (!zipWide || !rootWide) { PyMem_RawFree(zipWide); PyMem_RawFree(rootWide); PyConfig_Clear(&config); return false; }
        status = PyWideStringList_Append(&config.module_search_paths, zipWide);
        if (!PyStatus_Exception(status)) status = PyWideStringList_Append(&config.module_search_paths, rootWide);
        PyMem_RawFree(zipWide); PyMem_RawFree(rootWide);
        if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
        PyConfig_Clear(&config);
        if (PyStatus_Exception(status)) return false;
        main_ = PyThreadState_Get(); resourceRoot_ = root;
        return true;
    }
    Result Execute(Request &request) {
        Result result;
        current = request.control.get();
        if (!current->Stopped()) {
            if (!Initialize(request.options.resourceRoot)) result.errorCode = "initialization_failed";
            else {
                PyThreadState *interpreter = nullptr;
                PyInterpreterConfig configuration = {};
                configuration.use_main_obmalloc = 1; configuration.allow_fork = 0;
                configuration.allow_exec = 0; configuration.allow_threads = 0;
                configuration.allow_daemon_threads = 0; configuration.check_multi_interp_extensions = 0;
                configuration.gil = PyInterpreterConfig_SHARED_GIL;
                PyStatus status = Py_NewInterpreterFromConfig(&interpreter, &configuration);
                if (PyStatus_Exception(status)) result.errorCode = "initialization_failed";
                else {
                    PyObject *helper = PyImport_ImportModule("amber_python");
                    PyObject *function = helper ? PyObject_GetAttrString(helper, "execute") : nullptr;
                    PyObject *source = PyUnicode_DecodeUTF8(request.options.source.data(), request.options.source.size(), "strict");
                    PyObject *input = PyUnicode_DecodeUTF8(request.options.stdinText.data(), request.options.stdinText.size(), "strict");
                    PyObject *value = function && source && input ? PyObject_CallFunctionObjArgs(function, source, input, nullptr) : nullptr;
                    if (!value) { PyErr_Clear(); result.errorCode = "initialization_failed"; }
                    else if (value != Py_None) result.errorCode = "python_exception";
                    else { result.status = "completed"; result.exitCode = 0; }
                    Py_XDECREF(value); Py_XDECREF(input); Py_XDECREF(source); Py_XDECREF(function); Py_XDECREF(helper);
                    Py_EndInterpreter(interpreter); PyThreadState_Swap(main_);
                }
            }
        }
        // No Promise settles before this point: user code and interpreter are gone.
        if (current->cancelled) { result.status = "cancelled"; result.exitCode.reset(); result.errorCode = "cancelled"; }
        else if (current->timedOut) { result.status = "timed_out"; result.exitCode.reset(); result.errorCode = "timed_out"; }
        else if (current->outputLimit) { result.status = "failed"; result.exitCode = 1; result.errorCode = "output_limit"; }
        else if (result.errorCode) { result.status = "failed"; result.exitCode = 1; }
        result.stdoutText = std::move(current->stdoutText); result.stderrText = std::move(current->stderrText);
        current = nullptr; return result;
    }
    void Loop() {
        while (true) {
            Request request;
            { std::unique_lock<std::mutex> lock(mutex_); ready_.wait(lock, [&] { return stopping_ || !queue_.empty(); });
              if (queue_.empty() && stopping_) break;
              request = std::move(queue_.front()); queue_.pop_front(); }
            Result result;
            try { result = Execute(request); }
            catch (const std::exception &) { result.errorCode = "runtime_error"; current = nullptr; }
            request.options.source.clear(); request.options.stdinText.clear();
            { std::lock_guard<std::mutex> lock(request.client->mutex); request.client->controls.erase(request.id); }
            request.complete(std::move(result));
            request.client->settled.notify_all();
        }
        if (main_) { PyThreadState_Swap(main_); Py_FinalizeEx(); main_ = nullptr; }
    }
};
std::mutex ownerMutex;
std::shared_ptr<Owner> commonOwner;
std::shared_ptr<Owner> AcquireOwner() {
    std::lock_guard<std::mutex> lock(ownerMutex);
    if (!commonOwner) commonOwner = std::make_shared<Owner>();
    return commonOwner;
}
void ReleaseOwner(std::shared_ptr<Owner> &owner) {
    std::lock_guard<std::mutex> lock(ownerMutex);
    if (owner && commonOwner.use_count() == 2) {
        commonOwner.reset();
        owner.reset(); // Join/finalize before a new environment may initialize CPython.
    } else owner.reset();
}
} // namespace
struct Runtime::Impl {
    std::shared_ptr<Owner> owner = AcquireOwner();
    std::shared_ptr<Client> client = std::make_shared<Client>();
};
Runtime::Runtime() : impl_(std::make_shared<Impl>()) {}
Runtime::~Runtime() { Shutdown(); }
void Runtime::Execute(const std::string &id, Options options, Complete done) {
    if (id.empty() || id.size() > 256 || options.source.size() > 256 * 1024 || options.stdinText.size() > 64 * 1024 ||
        options.timeoutMs < 1 || options.timeoutMs > 60000 || options.resourceRoot.empty() ||
        options.resourceRoot.front() != '/' || options.resourceRoot.size() > 4096 || options.resourceRoot.find('\0') != std::string::npos)
        throw Exception("invalid_arguments", "Invalid embedded Python request");
    auto control = std::make_shared<Control>();
    control->deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(options.timeoutMs);
    { std::lock_guard<std::mutex> lock(impl_->client->mutex);
      if (impl_->client->closed) throw Exception("runtime_closed", "Embedded Python environment closed");
      if (!impl_->client->controls.emplace(id, control).second) throw Exception("duplicate_request", "Python request id already active"); }
    impl_->owner->Submit({id, std::move(options), impl_->client, control, std::move(done)});
}
void Runtime::Cancel(const std::string &id) {
    std::lock_guard<std::mutex> lock(impl_->client->mutex);
    auto found = impl_->client->controls.find(id); if (found != impl_->client->controls.end()) found->second->cancelled = true;
}
void Runtime::Shutdown() {
    if (!impl_) return;
    { std::unique_lock<std::mutex> lock(impl_->client->mutex); impl_->client->closed = true;
      for (auto &item : impl_->client->controls) item.second->cancelled = true;
      impl_->client->settled.wait(lock, [&] { return impl_->client->controls.empty(); }); }
    ReleaseOwner(impl_->owner);
}
size_t Runtime::PendingCount() const {
    std::lock_guard<std::mutex> lock(impl_->client->mutex); return impl_->client->controls.size();
}
std::string Version() { return PY_VERSION; }
} // namespace amber::python
