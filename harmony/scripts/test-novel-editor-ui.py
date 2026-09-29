#!/usr/bin/env python3
"""Check invalid-title feedback inside an already open novel editor.

Use a disposable local project. Clears the title and attempts an invalid save;
does not persist a chapter/material or make model requests. Leaves the draft open.
"""
import argparse
import importlib.util
from pathlib import Path


spec = importlib.util.spec_from_file_location('modal_ui', Path(__file__).with_name('test-modal-ui.py'))
modal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(modal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--title', required=True, help='Visible editor heading')
    parser.add_argument('--target', required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    device = modal.Device(args.target, args.output_dir)
    error = None
    try:
        nodes = device.dump('before')
        assert any(n.get('text') == args.title for n in nodes), 'Open the requested editor first'
        field = [n for n in nodes if n.get('type') == 'TextInput'][-1]
        if field.get('text'):
            device.click(field)
            # SDK KeyCode: CTRL_LEFT=2072, A=2017, DEL=2055.
            device.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 2072, 2017)
            device.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 2055)
            device.back()
        assert [n for n in device.dump() if n.get('type') == 'TextInput'][-1].get('text') == '', 'Title was not cleared'
        device.text('保存')
        nodes = device.dump('invalid-title')
        device.screenshot('invalid-title')
        title = [n for n in nodes if n.get('text') == args.title][-1]
        save = [n for n in nodes if n.get('text') == '保存'][-1]
        errors = [n for n in nodes if '标题不能为空' in n.get('text', '')]
        assert errors, 'Validation error did not appear'
        assert len(errors) == 1, 'Validation error is duplicated behind the editor'
        assert errors[0].get('text') == '标题不能为空', 'Validation error exposes an internal exception prefix'
        assert any(modal.bounds(title)[3] < modal.bounds(n)[1]
                   and modal.bounds(n)[3] <= modal.bounds(save)[1]
                   and modal.bounds(n) == modal.bounds(n, 'origBounds') for n in errors), \
            'Validation error is behind the editor instead of inside it'
        assert any(n.get('type') == 'TextArea' for n in nodes), 'Invalid save discarded the draft'
    except (AssertionError, RuntimeError, IndexError) as failure:
        error = str(failure)
    result = ('PASS' if error is None else 'FAIL') + ' novel-error-feedback' + (': ' + error if error else '')
    (args.output_dir / 'result.txt').write_text(result + '\n')
    print(result)
    if error is not None:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
