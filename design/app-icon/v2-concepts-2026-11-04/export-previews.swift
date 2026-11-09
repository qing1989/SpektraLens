#!/usr/bin/env swift
// Export sizes, apply preview masks, and lay out the original generated artwork.
// This script does not redraw or retouch any logo.
import AppKit
import CoreGraphics
import ImageIO

let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let fm = FileManager.default
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
let concepts = [
    ("01-negative-s", "A", "负形 S", "以负形保留 S 的识别，减少具象细节"),
    ("02-offset-discs", "B", "错位", "用两块半圆表达光与影，轮廓简洁"),
    ("03-overlap", "C", "叠光", "暖白底与橙色交叠，轻盈、柔和"),
    ("04-open-ring", "D", "余光", "以开口和留白暗示光线进入")
]

func context(_ width: Int, _ height: Int) -> CGContext {
    let result = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                           bytesPerRow: width * 4, space: colorSpace, bitmapInfo: bitmapInfo)!
    result.interpolationQuality = .high
    return result
}

func save(_ image: CGImage, _ filename: String) throws {
    let url = folder.appendingPathComponent(filename)
    try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    precondition(CGImageDestinationFinalize(dest), "Failed to write \(filename)")
}

enum Mask { case square, rounded, circle }
func preview(_ source: CGImage, _ size: Int, _ mask: Mask) -> CGImage {
    precondition(source.width == source.height, "Generated source must be square")
    let c = context(size, size)
    let s = Double(size)
    let rect = CGRect(x: 0, y: 0, width: s, height: s)
    if mask == .circle { c.addEllipse(in: rect); c.clip() }
    if mask == .rounded {
        c.addPath(CGPath(roundedRect: rect, cornerWidth: s * 0.225, cornerHeight: s * 0.225, transform: nil))
        c.clip()
    }
    c.draw(source, in: rect)
    return c.makeImage()!
}

let boardWidth = 1600
let boardHeight = 1440
let sheet = context(boardWidth, boardHeight)
sheet.setFillColor(CGColor(srgbRed: 0.953, green: 0.945, blue: 0.925, alpha: 1))
sheet.fill(CGRect(x: 0, y: 0, width: boardWidth, height: boardHeight))

func box(_ x: Double, _ top: Double, _ width: Double, _ height: Double) -> CGRect {
    CGRect(x: x, y: Double(boardHeight) - top - height, width: width, height: height)
}
func draw(_ image: CGImage, _ x: Double, _ top: Double, _ size: Double) {
    sheet.draw(image, in: box(x, top, size, size))
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: sheet, flipped: false)
func label(_ text: String, _ x: Double, _ top: Double, _ size: Double, _ secondary: Bool = false) {
    let font = NSFont(name: "PingFangSC-Regular", size: size) ?? NSFont.systemFont(ofSize: size)
    (text as NSString).draw(at: NSPoint(x: x, y: Double(boardHeight) - top - font.ascender), withAttributes: [
        .font: font,
        .foregroundColor: secondary ? NSColor(white: 0.46, alpha: 1) : NSColor(white: 0.10, alpha: 1)
    ])
}

label("SPEKTRA  /  光谱暗房", 64, 54, 44)
label("图标新方向 · 04 组抽象方案", 64, 116, 25, true)
label("2026.11.04", 1355, 73, 22, true)
var verification: [[String: Any]] = []

for (index, entry) in concepts.enumerated() {
    let (id, letter, name, caption) = entry
    let url = folder.appendingPathComponent("\(id)/source.png")
    guard let input = CGImageSourceCreateWithURL(url as CFURL, nil),
          let source = CGImageSourceCreateImageAtIndex(input, 0, nil) else {
        fatalError("Missing generated source: \(url.path)")
    }
    try save(preview(source, 1024, .square), "\(id)/icon-1024.png")
    try save(preview(source, 512, .rounded), "\(id)/preview-rounded.png")
    try save(preview(source, 512, .circle), "\(id)/preview-circle.png")
    for size in [96, 64, 48, 32] {
        try save(preview(source, size, .rounded), "\(id)/preview-\(size).png")
    }

    let x = index % 2 == 0 ? 64.0 : 820.0
    let top = index < 2 ? 204.0 : 804.0
    sheet.setFillColor(CGColor(srgbRed: 0.995, green: 0.991, blue: 0.982, alpha: 1))
    sheet.addPath(CGPath(roundedRect: box(x, top, 716, 560), cornerWidth: 24, cornerHeight: 24, transform: nil))
    sheet.fillPath()
    label("\(letter)  /  \(name)", x + 28, top + 26, 32)
    label(caption, x + 28, top + 77, 21, true)
    draw(preview(source, 608, .rounded), x + 28, top + 138, 304)
    draw(preview(source, 272, .circle), x + 424, top + 148, 136)
    label("圆形预览", x + 450, top + 302, 18, true)
    label("桌面小尺寸", x + 392, top + 350, 18, true)
    for (size, offset) in [(64, 392), (48, 488), (32, 568)] {
        draw(preview(source, size, .rounded), x + Double(offset), top + 388 + Double(64 - size), Double(size))
        label("\(size) px", x + Double(offset), top + 471, 17, true)
    }
    label("圆角预览", x + 136, top + 471, 18, true)
    verification.append(["id": id, "name": name, "sourcePixels": [source.width, source.height],
                         "previewMasks": ["rounded-square", "circle"], "sizes": [1024, 512, 96, 64, 48, 32]])
}

label("选型预览 · 原始图与缩放预览均保留 · 小图按标注像素排版", 64, 1390, 19, true)
NSGraphicsContext.restoreGraphicsState()
try save(sheet.makeImage()!, "contact-sheet.png")
let metadata: [String: Any] = ["date": "2026-11-04", "boardPixels": [boardWidth, boardHeight],
                             "appIntegrated": false, "method": "Resize, preview masking and layout only", "concepts": verification]
let data = try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys])
try data.write(to: folder.appendingPathComponent("export-metadata.json"))
print(String(data: data, encoding: .utf8)!)
