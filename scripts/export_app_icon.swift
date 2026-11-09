#!/usr/bin/env swift
// Deterministic icon sizing/packaging only; the original artwork is generated.
import AppKit
import CoreGraphics
import ImageIO

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let output = root.appendingPathComponent("design/app-icon/v1")
let resources = root.appendingPathComponent("android/app/src/main/res")
let sourceURL = output.appendingPathComponent("film-s-source.png")
let fm = FileManager.default
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue

func context(_ width: Int, _ height: Int) -> CGContext {
    let c = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                      bytesPerRow: width * 4, space: cs, bitmapInfo: bitmapInfo)!
    c.interpolationQuality = .high
    return c
}

func save(_ image: CGImage, _ url: URL) throws {
    try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    precondition(CGImageDestinationFinalize(destination), "Cannot write \(url.path)")
}

let input = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)!
let original = CGImageSourceCreateImageAtIndex(input, 0, nil)!
let scan = context(original.width, original.height)
scan.draw(original, in: CGRect(x: 0, y: 0, width: original.width, height: original.height))
let data = scan.data!.assumingMemoryBound(to: UInt8.self)
var x0 = original.width, y0 = original.height, x1 = 0, y1 = 0
var transparent = 0
for y in 0..<original.height {
    for x in 0..<original.width {
        let alpha = data[(y * original.width + x) * 4 + 3]
        if alpha == 0 { transparent += 1 }
        if alpha > 8 {
            x0 = min(x0, x); x1 = max(x1, x)
            y0 = min(y0, y); y1 = max(y1, y)
        }
    }
}
precondition(transparent > original.width * original.height / 3, "Source must preserve actual transparency")
precondition(x0 > 0 && y0 > 0 && x1 < original.width - 1 && y1 < original.height - 1)
let centerX = Double(x0 + x1) / 2
let centerY = Double(y0 + y1) / 2
var radius = 0.0
for y in y0...y1 {
    for x in x0...x1 where data[(y * original.width + x) * 4 + 3] > 8 {
        radius = max(radius, hypot(Double(x) - centerX, Double(y) - centerY))
    }
}
// Keep all substantial alpha within a 60dp diameter, inside the 66dp safe zone.
let crop = CGRect(x: x0 - 2, y: y0 - 2, width: x1 - x0 + 5, height: y1 - y0 + 5)
let mark = scan.makeImage()!.cropping(to: crop)!
func drawMark(_ c: CGContext, size: Double, viewport: Double) {
    let scale = 30.0 / radius * size / viewport
    let width = Double(mark.width) * scale, height = Double(mark.height) * scale
    c.draw(mark, in: CGRect(x: (size - width) / 2, y: (size - height) / 2, width: width, height: height))
}

enum Shape { case square, rounded, circle }
func icon(_ size: Int, shape: Shape = .square, themed: Bool = false) -> CGImage {
    let c = context(size, size)
    let s = Double(size)
    let rect = CGRect(x: 0, y: 0, width: s, height: s)
    if shape == .circle { c.addEllipse(in: rect); c.clip() }
    if shape == .rounded {
        c.addPath(CGPath(roundedRect: rect, cornerWidth: s * 0.225, cornerHeight: s * 0.225, transform: nil))
        c.clip()
    }
    c.setFillColor(themed ? CGColor(srgbRed: 0.88, green: 0.82, blue: 0.71, alpha: 1)
                         : CGColor(srgbRed: 17/255, green: 17/255, blue: 19/255, alpha: 1))
    c.fill(rect)
    if themed {
        let maskContext = context(size, size)
        drawMark(maskContext, size: s, viewport: 72)
        c.clip(to: rect, mask: maskContext.makeImage()!)
        c.setFillColor(CGColor(srgbRed: 0.27, green: 0.20, blue: 0.12, alpha: 1))
        c.fill(rect)
    } else { drawMark(c, size: s, viewport: 72) }
    return c.makeImage()!
}

for (density, layerSize) in [("mdpi",108),("hdpi",162),("xhdpi",216),("xxhdpi",324),("xxxhdpi",432)] {
    let c = context(layerSize, layerSize)
    drawMark(c, size: Double(layerSize), viewport: 108)
    try save(c.makeImage()!, resources.appendingPathComponent("drawable-\(density)/ic_spektra_film_s_foreground.png"))
}
let foreground = context(1080,1080)
drawMark(foreground, size: 1080, viewport: 108)
try save(foreground.makeImage()!, output.appendingPathComponent("adaptive-foreground-1080.png"))
try save(icon(1024), output.appendingPathComponent("icon-1024.png"))
try save(icon(512), output.appendingPathComponent("icon-512.png"))
try save(icon(1024, shape: .rounded), output.appendingPathComponent("preview-rounded.png"))
try save(icon(512, shape: .circle), output.appendingPathComponent("preview-circle.png"))
try save(icon(512, shape: .rounded, themed: true), output.appendingPathComponent("preview-themed.png"))

let sheet = context(1600, 1020)
sheet.setFillColor(CGColor(srgbRed: 0.055, green: 0.055, blue: 0.065, alpha: 1))
sheet.fill(CGRect(x: 0, y: 0, width: 1600, height: 1020))
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: sheet, flipped: false)
func label(_ text: String, _ x: Double, _ y: Double, _ size: Double, secondary: Bool = false) {
    let font = NSFont(name: "PingFangSC-Regular", size: size) ?? NSFont.systemFont(ofSize: size)
    (text as NSString).draw(at: NSPoint(x: x,y: y), withAttributes: [
        .font: font, .foregroundColor: secondary ? NSColor(white: 0.60, alpha: 1) : NSColor(white: 0.94, alpha: 1)
    ])
}
label("SpektraLens", 76, 907, 38)
label("01   光谱胶带 · Film S", 78, 858, 24, secondary: true)
sheet.draw(icon(600, shape: .rounded), in: CGRect(x: 76, y: 150, width: 600, height: 600))
sheet.draw(icon(280, shape: .circle), in: CGRect(x: 830, y: 470, width: 280, height: 280))
sheet.draw(icon(280, shape: .rounded, themed: true), in: CGRect(x: 1190, y: 470, width: 280, height: 280))
label("圆形遮罩", 904, 419, 23, secondary: true)
label("主题单色示意", 1250, 419, 23, secondary: true)
label("桌面小尺寸", 830, 319, 23, secondary: true)
for (size, x) in [(96,830),(64,1010),(48,1160),(32,1290)] {
    sheet.draw(icon(size, shape: .rounded), in: CGRect(x: x, y: 178, width: size, height: size))
    label("\(size) px", Double(x), 135, 19, secondary: true)
}
label("琥珀橙胶片  /  连续 S 形  /  炭黑背景", 78, 63, 23, secondary: true)
NSGraphicsContext.restoreGraphicsState()
try save(sheet.makeImage()!, output.appendingPathComponent("contact-sheet.png"))

let metadata: [String: Any] = [
    "source": "film-s-source.png", "sourcePixels": [original.width, original.height],
    "sourceAlphaBounds": [x0,y0,x1,y1], "transparentPixels": transparent,
    "adaptiveLayerDp": 108, "safeZoneDiameterDp": 66, "artworkDiameterDp": 60,
    "background": "#111113", "densities": ["mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi"]
]
let json = try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted,.sortedKeys])
try json.write(to: output.appendingPathComponent("export-metadata.json"))
print(String(data: json, encoding: .utf8)!)
