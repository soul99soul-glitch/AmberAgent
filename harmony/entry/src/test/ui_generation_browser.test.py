"""Real Chromium checks using the production ArkWeb injection and sandbox host.
Run: python3 harmony/entry/src/test/ui_generation_browser.test.py
Uses an existing Python Playwright installation and Google Chrome; installs nothing.
"""
import base64
import functools
import http.server
import json
import pathlib
import re
import threading
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[4]
VISUAL = ROOT / 'harmony/entry/src/main/resources/rawfile/visual'
EVIDENCE = pathlib.Path('/tmp/amber-ui-reveal')


def production_script():
    source = (ROOT / 'harmony/entry/src/main/ets/platform_impl/UIGenerationMotion.ets').read_text()
    template = re.search(r'return `([\s\S]*?)`;', source).group(1)
    return (template.replace('${css}', json.dumps((VISUAL / 'ui-generation.css').read_text()))
            .replace('${runtime}', (VISUAL / 'ui-generation.js').read_text().replace('export function ', 'function '))
            .replace('${adapter}', (VISUAL / 'amber-ui-generation.js').read_text()))


def card(dark=False):
    bg, ink = ('#20211f', '#ece8df') if dark else ('#faf9f5', '#25221d')
    return f'''<!doctype html><html><head><style>
      html,body{{margin:0;padding:8px;box-sizing:border-box;background:{bg};color:{ink};font:15px sans-serif}}
      main{{padding:16px;border:1px solid #9996;border-radius:16px;box-sizing:border-box}}
      h2{{font-size:18px;margin:0 0 12px}}p{{margin:8px 0}}.metric{{font-size:28px}}
      button{{min-height:44px;padding:0 16px;border:0;border-radius:12px;background:#b8623a;color:white}}
    </style></head><body><main data-amber-reveal><h2>今日专注</h2><p class="metric">90 分钟</p>
      <p>已完成 3 个专注时段</p><button>开始下一轮</button>
      <svg width="24" height="24" viewBox="0 0 24 24"><path d="M4 12 L10 18 L20 5" fill="none" stroke="#b8623a"/></svg>
      </main></body></html>'''


def started(page):
    page.wait_for_function("() => AmberUIGeneration.status() === 'started'", timeout=5000)


def setup(page, markup):
    page.goto('about:blank')
    page.set_content(markup, wait_until='domcontentloaded')
    page.evaluate(production_script())


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def run():
    EVIDENCE.mkdir(exist_ok=True)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=str(VISUAL)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
            page = browser.new_page(viewport={'width': 390, 'height': 820}, color_scheme='light')
            page.on('console', lambda message: print('browser:', message.type, message.text, flush=True)
                    if message.type in ('error', 'warning') else None)
            checks = 0
            # Production lexical factory/CSS, explicit inner target before outer #app/main.
            setup(page, '<div id="app" style="height:1200px"><main style="height:1000px">' +
                  '<section data-amber-reveal style="height:180px;width:280px;background:#eee"><h2>优先目标</h2><p>真实内容</p></section></main></div>')
            assert page.evaluate('AmberUIGeneration.play(false,false,false)') == 'pending'
            assert page.locator('[data-amber-reveal]').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            started(page)
            page.wait_for_function("() => document.querySelector('.t-gen') !== null")
            assert page.locator('#app').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            assert page.locator('[data-amber-reveal]').evaluate('(el)=>getComputedStyle(el).opacity') == '0'
            assert page.locator('.t-gen').evaluate('(el)=>getComputedStyle(el).pointerEvents') == 'none'
            page.evaluate("document.dispatchEvent(new Event('touchstart'))")
            assert page.locator('.t-gen').count() == 0
            assert page.locator('[data-amber-reveal]').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            page.evaluate('AmberUIGeneration.cancel(); AmberUIGeneration.play(false,false,false)')
            started(page)
            page.wait_for_function("() => document.querySelector('.t-gen') !== null")
            page.evaluate("document.querySelector('[data-amber-reveal]').style.height='210px'")
            page.wait_for_function("() => document.querySelector('.t-gen') === null")
            assert page.locator('[data-amber-reveal]').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            checks += 1

            setup(page, card())
            assert page.evaluate('AmberUIGeneration.play(false,true,false)') == 'skipped'
            assert page.locator('.t-gen').count() == 0
            setup(page, '<main style="height:1200px"><p>长页面</p></main>')
            page.evaluate('AmberUIGeneration.play(false,false,false)')
            page.wait_for_function("() => AmberUIGeneration.status() === 'skipped'")
            assert page.locator('main').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            checks += 1

            # A loading image must not hide content or spend the attempt before readiness.
            pending = []
            page.route('**/slow.png', lambda route: pending.append(route))
            setup(page, '<main style="height:180px"><h2>图片等待</h2><img width="40" height="40" src="http://127.0.0.1/slow.png"></main>')
            page.evaluate('AmberUIGeneration.play(false,false,false)')
            page.wait_for_timeout(350)
            assert page.evaluate('AmberUIGeneration.status()') == 'pending'
            assert page.locator('main').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
            pending.pop().fulfill(status=200, content_type='image/png', body=png)
            started(page)
            page.evaluate('AmberUIGeneration.cancel()')
            checks += 1

            setup(page, '<main style="height:180px"><img width="40" height="40" src="http://127.0.0.1/slow.png">超时仍可阅读</main>')
            page.evaluate('AmberUIGeneration.play(false,false,false)')
            page.wait_for_function("() => AmberUIGeneration.status() === 'skipped'", timeout=4500)
            assert page.locator('main').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
            pending.pop().abort()
            page.unroute('**/slow.png')
            checks += 1

            # Real production sandbox: generated iframe has no script permission.
            for width in (320, 390):
                for dark in (False, True):
                    page.set_viewport_size({'width': width, 'height': 820})
                    page.goto(f'http://127.0.0.1:{server.server_port}/svg-host.html')
                    page.evaluate('window.reports=[]; window.AmberVisualHost={postMessage: raw=>reports.push(JSON.parse(raw))}')
                    page.wait_for_function("() => document.querySelector('#content').contentDocument.body !== null")
                    page.evaluate('(html)=>AmberVisual.update({document:html,renderer:"html",revision:1,complete:true,dark:false})', card(dark))
                    page.wait_for_function('() => reports.length > 0')
                    page.wait_for_timeout(150)
                    height = page.evaluate('reports.at(-1).height')
                    page.evaluate(production_script())
                    page.evaluate('(dark)=>AmberUIGeneration.play(true,false,dark)', dark)
                    started(page)
                    page.wait_for_timeout(850)
                    frame = page.frames[1]
                    assert frame.locator('.t-gen').count() == 1, page.evaluate('({status:AmberUIGeneration.status(), reports, child:document.querySelector("#content").contentDocument.body.innerHTML.slice(-800)})')
                    assert frame.locator('.t-gen-svg path').count() > 0
                    assert page.evaluate('reports.at(-1).height') == height
                    layer = frame.locator('.t-gen').bounding_box()
                    target = frame.locator('main').bounding_box()
                    assert abs(layer['x'] - target['x']) <= 1 and abs(layer['y'] - target['y']) <= 1
                    assert abs(layer['width'] - target['width']) <= 1
                    assert frame.locator('.t-gen').evaluate('(el)=>getComputedStyle(el).getPropertyValue("--gen-line").trim()') == ('#6f6f6f' if dark else '#9a9a9a')
                    name = f'{width}-{"dark" if dark else "light"}'
                    page.screenshot(path=str(EVIDENCE / f'{name}-wireframe.png'))
                    page.wait_for_function("() => document.querySelector('#content').contentDocument.querySelector('.t-gen') === null", timeout=4000)
                    assert frame.locator('main').evaluate('(el)=>getComputedStyle(el).opacity') == '1'
                    assert page.evaluate('reports.at(-1).height') == height
                    assert page.locator('#content').get_attribute('sandbox') == 'allow-same-origin'
                    page.screenshot(path=str(EVIDENCE / f'{name}-complete.png'))
                    checks += 1
            browser.close()
            print(f'PASS: {checks} real-browser scenarios; evidence: {EVIDENCE}')
    finally:
        server.shutdown()


if __name__ == '__main__':
    run()
