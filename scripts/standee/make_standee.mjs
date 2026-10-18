// Counter standee print file (TASK-109): A5 and A4 pages, Hindi and English, font embedded.
// Uses the cloud's pdf-lib and fontkit and its Noto Sans Devanagari (OFL).
// Run: node scripts/standee/make_standee.mjs   (after `cd cloud && npm ci`)
import { readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const cloud = join(here, "..", "..", "cloud");
const require = createRequire(join(cloud, "package.json"));
require("regenerator-runtime/runtime"); // fontkit's Indic shaper needs it
const { PDFDocument, rgb } = require("pdf-lib");
const fontkit = require("@pdf-lib/fontkit");

const EN = "Members get 5% off. No card, no app.";
const HI = "मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।";
const SIZES = { A5: [419.53, 595.28], A4: [595.28, 841.89] };

const doc = await PDFDocument.create();
doc.registerFontkit(fontkit);
const regular = await doc.embedFont(readFileSync(join(cloud, "assets/fonts/NotoSansDevanagari-Regular.ttf")), { subset: true });
const bold = await doc.embedFont(readFileSync(join(cloud, "assets/fonts/NotoSansDevanagari-Bold.ttf")), { subset: true });

for (const [name, [w, h]] of Object.entries(SIZES)) {
  const page = doc.addPage([w, h]);
  const s = w / 419.53;
  page.drawRectangle({ x: 0, y: 0, width: w, height: h, color: rgb(1, 0.98, 0.94) });
  const centre = (text, font, size, y, color) => {
    const tw = font.widthOfTextAtSize(text, size);
    page.drawText(text, { x: (w - tw) / 2, y, size, font, color });
  };
  centre(HI, bold, 26 * s, h * 0.62, rgb(0.42, 0.11, 0.05));
  centre(EN, regular, 18 * s, h * 0.5, rgb(0.15, 0.12, 0.1));
  centre("Remu", bold, 14 * s, h * 0.12, rgb(0.55, 0.45, 0.35));
  centre(name, regular, 7 * s, h * 0.05, rgb(0.7, 0.65, 0.6));
}
writeFileSync(join(here, "standee.pdf"), await doc.save());
console.log("wrote scripts/standee/standee.pdf");
