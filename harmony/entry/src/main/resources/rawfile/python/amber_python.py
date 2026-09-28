"""App-owned data-processing helper. These restrictions are not an OS sandbox."""
import ast as _ast
import builtins as _builtins
import io as _io
import sys as _sys
import traceback as _traceback
import _amber_control as _control

# This is deliberately smaller than a general Python shell: no filesystem,
# network, subprocess, package installation or third-party native extensions.
_ALLOWED = frozenset({
    'array', 'base64', 'bisect', 'calendar', 'collections', 'collections.abc',
    'datetime', 'decimal', 'enum', 'fractions', 'functools', 'heapq', 'itertools',
    'json', 'math', 'pprint', 're', 'statistics', 'string', 'textwrap', 'unicodedata',
})
_FORBIDDEN = frozenset({
    'open', 'exec', 'eval', 'compile', 'globals', 'locals', 'vars', 'getattr',
    'setattr', 'delattr', 'breakpoint', 'help', 'exit', 'quit', '__import__',
    'builtins', 'sys', 'os', 'pathlib', 'socket', 'subprocess', 'ctypes',
    'importlib', 'inspect', 'modules', 'environ', 'system', 'popen',
})

class _RejectedSource(Exception):
    pass

class _Validate(_ast.NodeVisitor):
    def visit_Name(self, node):
        if node.id in _FORBIDDEN or node.id.startswith('_amber'):
            raise _RejectedSource('This name is unavailable in embedded Python: ' + node.id)
        self.generic_visit(node)

    def visit_Attribute(self, node):
        if node.attr.startswith('_') or node.attr in _FORBIDDEN:
            raise _RejectedSource('Private/reflective attributes are unavailable in embedded Python.')
        self.generic_visit(node)

    def visit_Import(self, node):
        for item in node.names:
            if item.name not in _ALLOWED:
                raise _RejectedSource('Module is unavailable in embedded Python: ' + item.name)
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        if node.level or node.module not in _ALLOWED:
            raise _RejectedSource('Module is unavailable in embedded Python: ' + str(node.module))
        if any(item.name.startswith('_') for item in node.names):
            raise _RejectedSource('Private imports are unavailable in embedded Python.')
        self.generic_visit(node)

class _Output:
    encoding = 'utf-8'
    errors = 'backslashreplace'
    def __init__(self, stderr):
        self._stderr = stderr
    def write(self, value):
        return _control.write(value, self._stderr)
    def flush(self):
        return None
    def isatty(self):
        return False

_original_import = _builtins.__import__
def _import(name, globals=None, locals=None, fromlist=(), level=0):
    if level or name not in _ALLOWED:
        raise ImportError('Module is unavailable in embedded Python: ' + name)
    return _original_import(name, globals, locals, fromlist, 0)

# Internal module imports retain normal builtins; only user globals receive the
# explicit import gate. A fresh subinterpreter discards all module mutations.
def execute(source, stdin):
    output = _Output(False)
    errors = _Output(True)
    _sys.stdin = _io.StringIO(stdin)
    _sys.stdout = output
    _sys.stderr = errors
    _sys.unraisablehook = _control.unraisable
    try:
        tree = _ast.parse(source, '<embedded-python>', 'exec')
        if sum(1 for _ in _ast.walk(tree)) > 20000:
            raise _RejectedSource('Source has too many syntax nodes.')
        _Validate().visit(tree)
        code = compile(tree, '<embedded-python>', 'exec')
        safe = {name: value for name, value in vars(_builtins).items()
                if name not in _FORBIDDEN and not name.startswith('_')}
        safe['__build_class__'] = _builtins.__build_class__
        safe['__import__'] = _import
        namespace = {'__builtins__': safe, '__name__': '__main__',
                     '_amber_checkpoint': _control.checkpoint, 'stdin': stdin}
        _control.checkpoint()
        _control.enable_trace()
        exec(code, namespace, namespace)
        _control.checkpoint()
        return None
    except BaseException:
        if _control.stopped():
            return None
        _traceback.print_exc(file=errors)
        return 'python_exception'
