"""Exercise the actual offline preview host with the locally installed Playwright browser."""
import functools
import http.server
import json
import pathlib
import subprocess
import tempfile
import threading

from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
HOST = ROOT / "harmony/entry/src/main/resources/rawfile/visual"
ARTIFACTS = pathlib.Path(tempfile.mkdtemp(prefix="amber-visual-check-"))


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


handler = functools.partial(QuietHandler, directory=str(HOST))
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f"http://127.0.0.1:{server.server_port}/host.html"

# Reuse the production document wrapper and scene normalizer, not a parallel test renderer.
fixtures = json.loads(subprocess.check_output([
    "node", str(ROOT / "harmony/chat/node_modules/tsx/dist/cli.mjs"), "-e", r'''
import { parseGenerativeWidgets, widgetDocument } from './harmony/chat/src/main/ets/chat/generative_widget.ts';
import { normalizeThreeScene } from './harmony/chat/src/main/ets/chat/three_scene.ts';
import { injectMiniAppShell } from './harmony/chat/src/main/ets/chat/miniapp/miniapp_shell.ts';
import { BRIDGE_JS } from './harmony/chat/src/main/ets/chat/miniapp/miniapp_bridge_js.ts';
const html = '<section id="trip"><h2>行程卡片回放</h2><ol><li id="a">第一站</li></ol>' +
  '<details id="detail"><summary>查看说明</summary><p>保持展开状态</p></details>' +
  '<div id="scroll" style="height:60px;overflow:auto"><p style="height:200px">可滚动内容</p></div></section>';
const svg = '<svg viewBox="0 0 360 160" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="g">' +
 '<stop stop-color="#b8623a"/><stop offset="1" stop-color="#d7b38c"/></linearGradient></defs>' +
 '<rect id="shape" x="24" y="36" width="88" height="88" rx="12" fill="url(#g)"/>';
const strictSvg = '<svg viewBox="0 0 360 160" xmlns="http://www.w3.org/2000/svg">' +
  '<rect id="strict-shape" x="24" y="36" width="88" height="88" rx="12" fill="#b8623a"/>' +
  '<text x="140" y="84" fill="#25221D">严格 SVG</text></svg>';
const strictNestedSvg = '<svg viewBox="0 0 360 160" xmlns="http://www.w3.org/2000/svg">' +
  '<svg id="nested" x="8" y="8" width="120" height="120"><circle cx="60" cy="60" r="40" fill="#55d6be"/></svg>' +
  '<text x="140" y="84" fill="#25221D">嵌套 SVG</text></svg>';
const object = (id, type, x) => ({id,type,color:'#b8623a',position:[x,0,0],rotation:[0,0,0],scale:[1,1,1],size:[1,1,1]});
const brokenSvgJson = JSON.stringify({title:'企业 CA',renderer:'svg',widget_code:svg+
  '<text x="140" y="84" fill="#25221D">企业 CA</text></svg>'}).slice(0,-2);
const recoveredSvg = parseGenerativeWidgets('```show-widget\n'+brokenSvgJson,false)[0].widget;
if (!recoveredSvg || !recoveredSvg.notice || recoveredSvg.actions.length) throw new Error('SVG recovery failed');
const mini = '<!doctype html><html><head><style>body{margin:0;background:#123456;color:#abcdef}' +
  'button{background:#55d6be;border-radius:12px;padding:12px;border:0}</style></head>' +
  '<body class="mini" style="padding:24px"><h1>互动卡片</h1><form id="form"><label>数量' +
  '<input id="value" type="number" value="0"></label><button id="increase" type="button">增加</button>' +
  '</form><p id="count">0</p><script>document.getElementById("increase").onclick=()=>{' +
  'const v=document.getElementById("value");v.value=String(Number(v.value)+1);' +
  'document.getElementById("count").textContent=v.value;};</script></body></html>';
console.log(JSON.stringify({
  first: widgetDocument(html,false),
  second: widgetDocument(html.replace('<li id="a">第一站</li>', '<li id="a">第一站</li><li id="b">第二站</li>'),false),
  dark: widgetDocument(html,true),
  svgFirst: widgetDocument(svg,false),
  svgFinal: widgetDocument(svg+'<circle id="orbit" cx="240" cy="80" r="40" fill="#d7b38c"/></svg>',false),
  svgRecovered: widgetDocument(recoveredSvg.html,false),
  strictDocument: widgetDocument(strictSvg,false),
  strictSvg,
  strictNoNamespace: strictSvg.replace(' xmlns="http://www.w3.org/2000/svg"', ''),
  strictBroken: strictSvg.replace('</svg>', ''),
  strictNonSvg: '<div>不是 SVG</div>',
  strictMultiRoot: strictSvg + '<svg xmlns="http://www.w3.org/2000/svg"/>',
  strictTailGarbage: strictSvg + 'tail-garbage',
  strictNestedSvg,
  sceneFirst: normalizeThreeScene({objects:[object('core','box',-1)]}),
  sceneFinal: normalizeThreeScene({objects:[object('core','box',-1),object('second','box',1)]}),
  miniPreview: widgetDocument(mini,false,true),
  miniRuntime: injectMiniAppShell(mini,BRIDGE_JS,'browser-regression'),
  miniCanvas: injectMiniAppShell('<!doctype html><html><head><style>' +
    'body{margin:0;touch-action:none!important}#game{touch-action:none!important}' +
    '</style></head><body><canvas id="game" width="300" height="180"></canvas>' +
    '<button id="plain">普通按钮</button><input id="field"><script>' +
    'window.canvasClicks=0;window.canvasDowns=0;window.buttonClicks=0;' +
    'const c=document.getElementById("game");c.onpointerdown=()=>window.canvasDowns++;' +
    'c.onclick=()=>window.canvasClicks++;document.getElementById("plain").onclick=()=>window.buttonClicks++;' +
    '</script></body></html>', BRIDGE_JS, 'canvas-regression', true),
}));
'''
], cwd=ROOT, text=True))

try:
    with sync_playwright() as playwright:
        installed_chrome = pathlib.Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
        browser = playwright.chromium.launch(headless=True,
            executable_path=str(installed_chrome) if installed_chrome.exists() else None,
            args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
        page = browser.new_page(viewport={"width": 390, "height": 820}, device_scale_factor=2)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.add_init_script("window.probeMessages=[];window.AmberVisualHost={postMessage:v=>window.probeMessages.push(JSON.parse(v))}")
        page.goto(url)
        page.wait_for_function("!!window.AmberVisual && !!document.querySelector('iframe').contentDocument")

        def update(document, revision, renderer="html", scene=None, dark=False, complete=False):
            page.evaluate("v=>window.AmberVisual.update(v)", {"document": document, "revision": revision,
                "renderer": renderer, "scene": scene, "dark": dark, "complete": complete})
            page.wait_for_function("r=>window.probeMessages.some(m=>m.revision===r)", arg=revision)
            latest = page.evaluate("window.probeMessages.at(-1)")
            assert latest["ok"], latest

        update(fixtures["first"], 1)
        page.evaluate("""() => {const d=document.querySelector('iframe').contentDocument;
          window.firstNode=d.getElementById('a');d.getElementById('detail').open=true;d.getElementById('scroll').scrollTop=40;}""")
        update(fixtures["second"], 2, complete=True)
        assert page.evaluate("""() => {const d=document.querySelector('iframe').contentDocument;
          return d.getElementById('a')===window.firstNode && d.getElementById('detail').open &&
          d.getElementById('scroll').scrollTop===40 && !!d.getElementById('b');}""")
        page.screenshot(path=str(ARTIFACTS / "html-progress.png"))

        for revision, markup, expected in [(20, '<p>A</p><p>B</p>', ['A', 'B']),
                (21, '<p>A</p><p>C</p><p>B</p>', ['A', 'C', 'B'])]:
            doc = fixtures["first"].split('<body>')[0] + '<body>' + markup + '</body></html>'
            update(doc, revision)
            assert page.evaluate("[...document.querySelector('iframe').contentDocument.querySelectorAll('p')].map(n=>n.textContent)") == expected
        update(fixtures["second"], 22, complete=True)

        # Even unsanitized generated scripts cannot execute or reach the native host in the iframe.
        unsafe = fixtures["first"].replace("</body>", '<script>parent.window.pwned=true</script><img src="https://example.invalid/x" onerror="parent.window.pwned=true"></body>')
        update(unsafe, 3)
        assert not page.evaluate("!!window.pwned")
        assert page.locator("iframe").get_attribute("sandbox") == "allow-same-origin"

        update(fixtures["svgFirst"], 4, renderer="svg")
        page.evaluate("window.svgNode=document.querySelector('iframe').contentDocument.getElementById('shape')")
        update(fixtures["svgFinal"], 5, renderer="svg", complete=True)
        assert page.evaluate("document.querySelector('iframe').contentDocument.getElementById('shape')===window.svgNode")
        page.screenshot(path=str(ARTIFACTS / "svg-progress.png"))
        update(fixtures["svgRecovered"], 23, renderer="svg", complete=True)
        assert page.locator("iframe").content_frame.locator("svg text").text_content() == "企业 CA"
        assert page.evaluate("window.probeMessages.at(-1).content")
        page.screenshot(path=str(ARTIFACTS / "svg-recovered.png"))

        # Legacy SVG keeps its HTML-parser/partial-update behavior on the Three host.
        assert page.evaluate("typeof window.AmberThree === 'object'")

        strict = browser.new_page(viewport={"width": 390, "height": 820}, device_scale_factor=2)
        strict.add_init_script("window.probeMessages=[];window.AmberVisualHost={postMessage:v=>window.probeMessages.push(JSON.parse(v))}")
        strict.goto(f"http://127.0.0.1:{server.server_port}/svg-host.html")
        strict.wait_for_function("!!window.AmberVisual && !!document.querySelector('iframe').contentDocument")
        assert strict.evaluate("typeof window.AmberThree === 'undefined'")
        assert not strict.evaluate("performance.getEntriesByName('three.min.js').length || performance.getEntriesByName('three-runtime.js').length")

        # The non-Three host still keeps the legacy static/partial SVG path when strict mode is off.
        strict.evaluate("v=>window.AmberVisual.update(v)", {
            "document": fixtures["svgFirst"], "rawSVG": "", "renderer": "svg",
            "strictSvg": False, "revision": 25, "complete": False, "dark": False,
        })
        strict.wait_for_function("r=>window.probeMessages.some(m=>m.revision===r)", arg=25)
        assert strict.locator("iframe").content_frame.locator("#shape").count() == 1

        def update_strict(raw_svg, revision, expected_ok, expected_content):
            strict.evaluate("v=>window.AmberVisual.update(v)", {
                "document": fixtures["strictDocument"], "rawSVG": raw_svg, "renderer": "svg",
                "strictSvg": True, "revision": revision, "complete": True, "dark": False,
            })
            strict.wait_for_function("r=>window.probeMessages.some(m=>m.revision===r && m.validationRevision===r)", arg=revision)
            message = strict.evaluate("r=>window.probeMessages.find(m=>m.revision===r && m.validationRevision===r)", revision)
            assert message["validationRevision"] == revision
            assert message["validationOk"] is expected_ok
            assert message["content"] is expected_content
            return message

        update_strict(fixtures["strictSvg"], 30, True, True)
        assert strict.locator("iframe").content_frame.locator("#strict-shape").count() == 1
        update_strict(fixtures["strictBroken"], 31, False, True)
        assert strict.locator("iframe").content_frame.locator("#strict-shape").count() == 1
        update_strict(fixtures["strictNonSvg"], 32, False, True)
        assert strict.locator("iframe").content_frame.locator("#strict-shape").count() == 1
        no_namespace_message = update_strict(fixtures["strictNoNamespace"], 33, True, True)
        assert 'xmlns="http://www.w3.org/2000/svg"' in no_namespace_message["validationSvg"]
        export_page = browser.new_page()
        export_page.set_content("<!doctype html><body></body>")
        standalone_probe = export_page.evaluate("""async ({raw, canonical}) => {
          async function draw(source) {
            const url = URL.createObjectURL(new Blob([source], {type:'image/svg+xml'}));
            const image = new Image(); image.src = url;
            try { await image.decode(); } catch (error) {
              URL.revokeObjectURL(url); return {decoded:false, colored:0, error:String(error)};
            }
            const canvas = document.createElement('canvas'); canvas.width = 360; canvas.height = 160;
            const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
            URL.revokeObjectURL(url);
            const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
            let colored = 0;
            for (let index = 0; index < pixels.length; index += 4) {
              if (pixels[index + 3] > 0 && Math.max(pixels[index], pixels[index + 1], pixels[index + 2]) -
                Math.min(pixels[index], pixels[index + 1], pixels[index + 2]) > 30) colored++;
            }
            return {decoded:true, colored, error:''};
          }
          return {raw: await draw(raw), canonical: await draw(canonical)};
        }""", {"raw": fixtures["strictNoNamespace"], "canonical": no_namespace_message["validationSvg"]})
        assert standalone_probe["raw"]["decoded"] is False
        assert standalone_probe["canonical"]["decoded"] is True
        assert standalone_probe["canonical"]["colored"] > 20
        export_page.close()
        assert strict.locator("iframe").content_frame.locator("#strict-shape").count() == 1
        namespace_probe = strict.evaluate("""() => {
          const svg = document.querySelector('iframe').contentDocument.querySelector('svg');
          const rect = document.querySelector('iframe').contentDocument.getElementById('strict-shape');
          const box = rect.getBBox();
          return {root: svg.namespaceURI, rect: rect.namespaceURI, width: box.width, height: box.height};
        }""")
        assert namespace_probe["root"] == "http://www.w3.org/2000/svg"
        assert namespace_probe["rect"] == "http://www.w3.org/2000/svg"
        assert namespace_probe["width"] > 0 and namespace_probe["height"] > 0
        update_strict(fixtures["strictNestedSvg"], 34, True, True)
        assert strict.locator("iframe").content_frame.locator("#nested circle").count() == 1
        update_strict(fixtures["strictMultiRoot"], 35, False, True)
        assert strict.locator("iframe").content_frame.locator("#nested circle").count() == 1
        update_strict(fixtures["strictTailGarbage"], 36, False, True)
        assert strict.locator("iframe").content_frame.locator("#nested circle").count() == 1
        strict.evaluate("window.AmberVisual.setActive(false);window.AmberVisual.setInteracting(true);window.AmberVisual.dispose()")
        assert not strict.evaluate("!!window.__threeDisposeError")
        strict.close()

        # A WebGL failure must show the trusted static cover and keep interaction disabled.
        fallback = browser.new_page(viewport={"width": 390, "height": 820}, device_scale_factor=2)
        fallback.add_init_script("window.probeMessages=[];window.AmberVisualHost={postMessage:v=>window.probeMessages.push(JSON.parse(v))}")
        fallback.goto(f"http://127.0.0.1:{server.server_port}/host.html")
        fallback.wait_for_function("!!window.AmberVisual && !!document.querySelector('iframe').contentDocument")
        fallback.evaluate("""({document, scene}) => {
          window.AmberThree.update = () => ({ok:false,error:'WebGL renderer unavailable',objectCount:0});
          window.AmberVisual.update({document, rawSVG:'', renderer:'threejs', scene,
            strictSvg:false, revision:40, complete:true, dark:false});
        }""", {"document": fixtures["strictDocument"], "scene": fixtures["sceneFinal"]})
        fallback.wait_for_function("r=>window.probeMessages.some(m=>m.revision===r)", arg=40)
        fallback_message = fallback.evaluate("window.probeMessages.find(m=>m.revision===40)")
        assert fallback_message["ok"] is False
        assert fallback_message["content"] is True
        assert "WebGL" in fallback_message["error"]
        assert fallback.locator("#scene").is_hidden()
        assert fallback.locator("iframe").is_visible()
        assert fallback.locator("iframe").content_frame.locator("#strict-shape").count() == 1
        fallback.evaluate("window.AmberVisual.setActive(false);window.AmberVisual.setInteracting(true);window.AmberVisual.dispose()")
        fallback.close()

        update("", 6, renderer="threejs", scene=fixtures["sceneFirst"])
        page.evaluate("window.firstCanvas=document.querySelector('#scene canvas')")
        first_canvas = page.locator("#scene canvas")
        first_canvas.screenshot(path=str(ARTIFACTS / "three-first.png"))
        first_image = Image.open(ARTIFACTS / "three-first.png").convert("RGB")
        first_colored_pixels = sum(1 for red, green, blue in first_image.getdata()
            if max(red, green, blue) - min(red, green, blue) > 30 and min(red, green, blue) < 220)
        assert first_colored_pixels > 20, "first Three.js frame contains no colored geometry"
        update("", 7, renderer="threejs", scene=fixtures["sceneFinal"], complete=True)
        assert page.evaluate("document.querySelector('#scene canvas')===window.firstCanvas")
        assert page.evaluate("window.probeMessages.at(-1).content")
        page.evaluate("window.AmberVisual.setInteracting(true)")
        page.mouse.move(160, 110)
        page.mouse.down()
        page.mouse.move(235, 150, steps=8)
        page.mouse.up()
        page.screenshot(path=str(ARTIFACTS / "three-interactive.png"))
        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pagehide'));window.dispatchEvent(new PageTransitionEvent('pageshow'))")
        page.wait_for_function("!!document.querySelector('#scene canvas')")
        assert page.evaluate("window.probeMessages.at(-1).content")

        page.evaluate("document.documentElement.style.background='#14110e'")
        update(fixtures["dark"], 8, dark=True, complete=True)
        page.screenshot(path=str(ARTIFACTS / "html-dark.png"))
        assert not page.evaluate("!!document.querySelector('#scene canvas')")
        page.evaluate("window.AmberVisual.dispose()")
        assert not errors, errors

        mini = browser.new_page(viewport={"width":390,"height":280})
        # The production bootstrap keeps doctype/CSS/form/scripts; direct network is blocked by CSP.
        mini.set_content(fixtures["miniRuntime"])
        assert mini.evaluate("document.compatMode") == "CSS1Compat"
        assert mini.locator("body").evaluate("n=>getComputedStyle(n).backgroundColor") == "rgb(18, 52, 86)"
        assert mini.locator("body").evaluate("n=>getComputedStyle(n).paddingTop") == "24px"
        mini.locator("#increase").click()
        assert mini.locator("#count").inner_text() == "1"
        mini.locator("#value").fill("7")
        mini.locator("#increase").click()
        assert mini.locator("#count").inner_text() == "8"
        assert not mini.evaluate("!!window.XMLHttpRequest || !!window.WebSocket || !!navigator.clipboard")
        mini.screenshot(path=str(ARTIFACTS / "miniapp-inline-interactive.png"))
        static = browser.new_page(viewport={"width":390,"height":280})
        static.set_content(fixtures["miniPreview"])
        assert static.locator("body").evaluate("n=>getComputedStyle(n).backgroundColor") == "rgb(18, 52, 86)"
        assert static.locator("body").get_attribute("class") == "mini"
        assert static.locator("#value").count() == 1
        static.locator("#increase").click()
        assert static.locator("#count").inner_text() == "0", "streaming app scripts executed"

        canvas_page = browser.new_page(viewport={"width":390,"height":400})
        canvas_page.evaluate("""() => {
          window.focusMessages=[];
          window.AmberNative={postInteraction:raw=>{
            const m=JSON.parse(raw);focusMessages.push(m);
            window.__amberSetCanvasInteraction(m.focused);
          }};
        }""")
        canvas_page.set_content(fixtures["miniCanvas"])
        game = canvas_page.locator("#game")
        assert game.evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"
        assert canvas_page.locator("body").evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"
        # Dragging and synthetic clicks must not activate a canvas.
        canvas_page.mouse.move(60,60)
        canvas_page.mouse.down()
        canvas_page.mouse.move(180,90,steps=8)
        canvas_page.mouse.up()
        game.evaluate("n=>n.click()")
        assert canvas_page.evaluate("focusMessages.filter(m=>m.focused).length") == 0
        assert canvas_page.evaluate("canvasClicks+canvasDowns") == 0
        game.click()
        assert canvas_page.evaluate("focusMessages.at(-1).sessionToken") == "canvas-regression"
        assert game.evaluate("n=>getComputedStyle(n).touchAction") == "none"
        assert canvas_page.evaluate("canvasClicks+canvasDowns") == 0, "activation tap reached game logic"
        game.click()
        assert canvas_page.evaluate("canvasClicks") == 1
        assert canvas_page.evaluate("canvasDowns") == 1
        canvas_page.locator("#plain").click()
        assert canvas_page.evaluate("buttonClicks") == 1
        assert game.evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"
        game.click()
        canvas_page.locator("#field").fill("一次点按即可填写")
        assert game.evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"

        # Real touch input: a vertical swipe must scroll the timeline around the canvas,
        # while a horizontal swipe must still rotate the scene.
        touch = browser.new_page(viewport={"width": 390, "height": 820}, is_mobile=True, has_touch=True)
        touch.goto(url)
        touch.wait_for_function("!!window.AmberVisual")
        touch.evaluate("""scene => {
          const Base = window.AmberThreeThree.OrbitControls;
          window.AmberThreeThree.OrbitControls = class extends Base {
            constructor(camera, canvas) { super(camera, canvas); window.touchControls=this; }
          };
          document.documentElement.style.overflow='auto';
          document.body.style.overflow='visible';
          document.body.insertAdjacentHTML('afterbegin','<div style="height:300px">聊天上文</div>');
          document.body.insertAdjacentHTML('beforeend','<div style="height:1600px">聊天下文</div>');
          window.AmberVisual.update({renderer:'threejs',scene,revision:1,complete:true});
          window.scrollTo(0,200);
        }""", fixtures["sceneFinal"])
        touch.wait_for_function("!!window.touchControls")
        assert touch.locator("canvas").evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"
        cdp = touch.context.new_cdp_session(touch)

        def swipe(x0, y0, x1, y1):
            cdp.send("Input.dispatchTouchEvent", {"type": "touchStart",
                "touchPoints": [{"x": x0, "y": y0}]})
            for step in range(1, 13):
                cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{
                    "x": x0 + (x1-x0)*step/12, "y": y0 + (y1-y0)*step/12}]})
                touch.wait_for_timeout(20)
            cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
            touch.wait_for_timeout(180)

        rect = touch.locator("canvas").bounding_box()
        start_y = rect["y"] + rect["height"]*0.7
        scroll_before = touch.evaluate("scrollY")
        swipe(195, start_y, 195, start_y-130)
        assert touch.evaluate("scrollY") > scroll_before + 60, "vertical canvas swipe stole timeline scrolling"
        rect = touch.locator("canvas").bounding_box()
        camera_before = touch.evaluate("touchControls.object.position.toArray()")
        swipe(80, rect["y"]+rect["height"]/2, 260, rect["y"]+rect["height"]/2)
        assert touch.evaluate("touchControls.object.position.toArray()") == camera_before, "inactive canvas consumed a drag"
        touch.evaluate("window.AmberVisual.setInteracting(true)")
        assert touch.locator("canvas").evaluate("n=>getComputedStyle(n).touchAction") == "none"
        swipe(80, rect["y"]+rect["height"]/2, 260, rect["y"]+rect["height"]/2)
        camera_after = touch.evaluate("touchControls.object.position.toArray()")
        assert any(abs(a-b)>0.05 for a,b in zip(camera_before,camera_after)), "horizontal rotation stopped working"
        touch.evaluate("window.AmberVisual.setInteracting(false)")
        assert not touch.evaluate("touchControls.enabled")
        assert touch.locator("canvas").evaluate("n=>getComputedStyle(n).touchAction") == "pan-y"
        touch.screenshot(path=str(ARTIFACTS / "three-touch-scroll.png"))
        browser.close()
    print(json.dumps({"pass": True, "checks": ["stable HTML nodes", "details and scroll preserved", "sandbox scripts blocked",
        "legacy partial SVG", "legacy SVG on SVG host", "strict SVG XML validation", "missing xmlns namespace and bbox",
        "invalid SVG keeps last snapshot", "SVG host excludes Three.js", "WebGL failure shows static cover and disables interaction",
        "first Three.js screenshot contains colored geometry",
        "real WebGL canvas reused", "vertical touch scroll", "horizontal touch rotation", "dark theme", "GPU disposal",
        "MiniApp styles preserved", "MiniApp form and click interaction", "streaming MiniApp scripts blocked"], "artifacts": str(ARTIFACTS)}, ensure_ascii=False))
finally:
    server.shutdown()
    server.server_close()
