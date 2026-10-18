"""Builds docs/plan/*.md into two-column A4 PDFs in docs/plan/pdf using headless Chrome."""
import re, sys, subprocess, pathlib, tempfile, markdown
ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "docs/plan"; OUT = SRC / "pdf"; HTML = SRC / "build"
OUT.mkdir(exist_ok=True); HTML.mkdir(exist_ok=True)
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
CSS = """
@page { size: A4; margin: 10mm 9mm 11mm; }
* { box-sizing: border-box; }
body { margin: 0; font: 8.9pt/1.36 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #141414; }
.head { border-bottom: 1.2pt solid #1e3b32; padding-bottom: 4pt; margin-bottom: 6pt; }
.head .t { font-size: 17pt; font-weight: 700; color: #1e3b32; }
.head .s { font-size: 8.6pt; color: #444; margin-top: 1pt; }
.cols { column-count: 2; column-gap: 7mm; column-rule: 0.4pt solid #d0d0d0; }
h1 { display: none; }
h2 { font-size: 11.2pt; color: #1e3b32; margin: 7pt 0 3pt; padding-bottom: 1pt; border-bottom: 0.5pt solid #9aa; break-after: avoid; }
h3 { font-size: 9.6pt; margin: 6pt 0 2pt; break-after: avoid; }
p { margin: 0 0 4pt; }
ul, ol { margin: 0 0 4pt 0; padding-left: 12pt; }
li { margin-bottom: 1.6pt; }
table { width: 100%; border-collapse: collapse; font-size: 7.5pt; line-height: 1.28; margin: 2pt 0 6pt; }
th, td { border: 0.4pt solid #b9b9b9; padding: 1.6pt 3pt; vertical-align: top; text-align: left; }
th { background: #e9efec; }
tr { break-inside: avoid; }
pre { white-space: pre-wrap; word-break: break-word; font: 7.2pt/1.3 Menlo, Consolas, monospace; background: #f3f5f4;
      border: 0.4pt solid #c9d1cd; padding: 3.5pt 4pt; margin: 2pt 0 5pt; }
code { font-family: Menlo, Consolas, monospace; font-size: 7.9pt; }
pre code { font-size: inherit; }
blockquote { margin: 0 0 5pt; padding: 2pt 0 2pt 6pt; border-left: 2pt solid #1e3b32; color: #222; }
blockquote p { margin-bottom: 3pt; }
strong { color: #000; }
"""
def reflow(text):
    """Join hard-wrapped lines inside prompt blocks so they wrap cleanly in a narrow column.
    Blocks that start with shell commands are left untouched."""
    out, lines, i = [], text.split("\n"), 0
    while i < len(lines):
        if lines[i].startswith("```"):
            j = i + 1
            while not lines[j].startswith("```"): j += 1
            block = lines[i + 1:j]
            if not any(l.startswith(("git ", "tools/")) for l in block):
                merged = []
                for l in block:
                    st = l.strip()
                    starts_item = st.startswith("- ") or (st[:2].rstrip(".").isdigit() and st[1:3].startswith("."))
                    if merged and st and not starts_item and merged[-1].strip():
                        merged[-1] = merged[-1].rstrip() + " " + st
                    else:
                        merged.append(l)
                block = merged
            out += [lines[i]] + block + [lines[j]]; i = j + 1
        else:
            out.append(lines[i]); i += 1
    return "\n".join(out)
def build(md_path):
    text = reflow(md_path.read_text())
    meta = {}
    m = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if m:
        for line in m.group(1).splitlines():
            k, _, v = line.partition(":"); meta[k.strip()] = v.strip().strip('"')
        text = text[m.end():]
    body = markdown.markdown(text, extensions=["tables", "fenced_code", "sane_lists"])
    html = f"""<!doctype html><html><head><meta charset="utf-8"><title>{meta.get('title','')}</title>
<style>{CSS}</style></head><body><div class="head"><div class="t">{meta.get('title','')}</div>
<div class="s">{meta.get('subtitle','')}</div></div><div class="cols">{body}</div></body></html>"""
    h = HTML / (md_path.stem + ".html"); h.write_text(html)
    pdf = OUT / (md_path.stem + ".pdf")
    pdf.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as prof:
        # Chrome writes the PDF but on some macOS versions never exits, so wait for the
        # "bytes written" line and then stop it.
        proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                                 "--no-pdf-header-footer", f"--user-data-dir={prof}", f"--print-to-pdf={pdf}", h.as_uri()],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            for line in proc.stdout or []:
                if "bytes written to file" in line:
                    break
        finally:
            proc.kill(); proc.wait()
    if not pdf.exists() or pdf.stat().st_size == 0:
        sys.exit(f"PDF not written: {pdf}")
    return pdf
for p in sorted(SRC.glob("0*.md")):
    print(build(p))
