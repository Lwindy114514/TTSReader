import os, random, sys

def make_novel(path, encoding, chapters=12, chars_per_para=160):
    random.seed(42)
    cn_words = list("天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏金生丽水玉出昆冈剑号巨阙珠称夜光")
    out = []
    for ch in range(1, chapters + 1):
        out.append(f"第{ch}章 风起云涌")
        out.append("")
        for p in range(30):
            para = "".join(random.choice(cn_words) for _ in range(chars_per_para))
            out.append(para)
            out.append("")
    text = "\n".join(out)
    with open(path, "w", encoding=encoding, newline="\n") as f:
        f.write(text)
    return len(text)

base = r"C:\Users\LYD\Documents\Default Project\NovelTTSPlayer\testdata"
os.makedirs(base, exist_ok=True)

# UTF-8
n = make_novel(os.path.join(base, "novel_utf8.txt"), "utf-8", chapters=6)
# UTF-8 with BOM
p = os.path.join(base, "novel_utf8bom.txt")
make_novel(p, "utf-8", chapters=6)
with open(p, "rb") as f: data = f.read()
with open(p, "wb") as f: f.write(b"\xef\xbb\xbf" + data)
# GBK
make_novel(os.path.join(base, "novel_gbk.txt"), "gbk", chapters=6)
# GB18030
make_novel(os.path.join(base, "novel_gb18030.txt"), "gb18030", chapters=6)
# Large novel ~1.5 million chars UTF-8
n = make_novel(os.path.join(base, "novel_big_utf8.txt"), "utf-8", chapters=300, chars_per_para=180)
print("chars_big =", n)
print("done")