// Draws the RadarNebo desktop icons: the app (a radar sweep with targets) and the server console (a server
// broadcasting radar waves), in the same style.
//
//   swiftc -O tools/make-icons.swift -o /tmp/make-icons && /tmp/make-icons <output dir>
//
// Writes <name>-<size>.png for the macOS icon sizes; tools/make-icons.sh packs them into .icns, .ico and .png.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let cyan = CGColor(red: 0.31, green: 0.82, blue: 0.91, alpha: 1)
let red = CGColor(red: 1.0, green: 0.35, blue: 0.37, alpha: 1)
let amber = CGColor(red: 0.96, green: 0.65, blue: 0.14, alpha: 1)
let green = CGColor(red: 0.27, green: 0.82, blue: 0.48, alpha: 1)

func color(_ c: CGColor, _ alpha: CGFloat) -> CGColor { c.copy(alpha: alpha)! }

/// The rounded square every icon sits on, inset like macOS app icons (824 of 1024).
func background(_ ctx: CGContext) {
    let body = CGRect(x: 100, y: 100, width: 824, height: 824)
    let shape = CGPath(roundedRect: body, cornerWidth: 185, cornerHeight: 185, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 28, color: CGColor(gray: 0, alpha: 0.45))
    ctx.addPath(shape)
    ctx.setFillColor(CGColor(red: 0.03, green: 0.07, blue: 0.11, alpha: 1))
    ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(shape)
    ctx.clip()
    let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [
        CGColor(red: 0.07, green: 0.16, blue: 0.24, alpha: 1),
        CGColor(red: 0.02, green: 0.05, blue: 0.08, alpha: 1),
    ] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: CGPoint(x: 512, y: 924), end: CGPoint(x: 512, y: 100), options: [])
    // A faint grid, like the map's.
    ctx.setStrokeColor(color(cyan, 0.06))
    ctx.setLineWidth(3)
    for i in stride(from: 100, through: 924, by: 103) {
        ctx.move(to: CGPoint(x: CGFloat(i), y: 100)); ctx.addLine(to: CGPoint(x: CGFloat(i), y: 924))
        ctx.move(to: CGPoint(x: 100, y: CGFloat(i))); ctx.addLine(to: CGPoint(x: 924, y: CGFloat(i)))
    }
    ctx.strokePath()
    ctx.restoreGState()
    ctx.addPath(shape)
    ctx.setStrokeColor(color(cyan, 0.28))
    ctx.setLineWidth(6)
    ctx.strokePath()
}

func blip(_ ctx: CGContext, _ at: CGPoint, _ c: CGColor, _ r: CGFloat) {
    ctx.setFillColor(color(c, 0.22))
    ctx.fillEllipse(in: CGRect(x: at.x - r * 2.4, y: at.y - r * 2.4, width: r * 4.8, height: r * 4.8))
    ctx.setFillColor(c)
    ctx.fillEllipse(in: CGRect(x: at.x - r, y: at.y - r, width: r * 2, height: r * 2))
}

/// Radar rings, crosshair and a sweep fading behind its leading edge (angles in degrees, counter-clockwise from east).
func radar(_ ctx: CGContext, center c: CGPoint, radius r: CGFloat, sweepTo edge: CGFloat) {
    ctx.setStrokeColor(color(cyan, 0.38))
    ctx.setLineWidth(r * 0.03)
    for f in [0.36, 0.68, 1.0] as [CGFloat] {
        ctx.strokeEllipse(in: CGRect(x: c.x - r * f, y: c.y - r * f, width: r * 2 * f, height: r * 2 * f))
    }
    ctx.setStrokeColor(color(cyan, 0.2))
    ctx.setLineWidth(r * 0.02)
    ctx.move(to: CGPoint(x: c.x - r, y: c.y)); ctx.addLine(to: CGPoint(x: c.x + r, y: c.y))
    ctx.move(to: CGPoint(x: c.x, y: c.y - r)); ctx.addLine(to: CGPoint(x: c.x, y: c.y + r))
    ctx.strokePath()
    let span: CGFloat = 70
    let steps = 40
    for i in 0..<steps {
        let a0 = (edge + span * CGFloat(i) / CGFloat(steps)) * .pi / 180
        let a1 = (edge + span * CGFloat(i + 1) / CGFloat(steps)) * .pi / 180
        ctx.move(to: c)
        ctx.addArc(center: c, radius: r, startAngle: a0, endAngle: a1, clockwise: false)
        ctx.closePath()
        ctx.setFillColor(color(cyan, 0.42 * pow(1 - CGFloat(i) / CGFloat(steps), 2)))
        ctx.fillPath()
    }
    let a = edge * .pi / 180
    ctx.setStrokeColor(cyan)
    ctx.setLineWidth(r * 0.035)
    ctx.setLineCap(.round)
    ctx.move(to: c)
    ctx.addLine(to: CGPoint(x: c.x + r * cos(a), y: c.y + r * sin(a)))
    ctx.strokePath()
    ctx.setFillColor(cyan)
    ctx.fillEllipse(in: CGRect(x: c.x - r * 0.05, y: c.y - r * 0.05, width: r * 0.1, height: r * 0.1))
}

func appIcon(_ ctx: CGContext) {
    background(ctx)
    radar(ctx, center: CGPoint(x: 512, y: 512), radius: 330, sweepTo: 40)
    blip(ctx, CGPoint(x: 640, y: 640), red, 24)
    blip(ctx, CGPoint(x: 372, y: 600), amber, 19)
    blip(ctx, CGPoint(x: 560, y: 330), amber, 15)
}

func serverIcon(_ ctx: CGContext) {
    background(ctx)
    // Waves broadcast from the top of the stack.
    let source = CGPoint(x: 512, y: 600)
    ctx.setLineCap(.round)
    for (i, r) in ([95, 175, 255] as [CGFloat]).enumerated() {
        ctx.setStrokeColor(color(cyan, 0.9 - CGFloat(i) * 0.25))
        ctx.setLineWidth(22)
        ctx.addArc(center: source, radius: r, startAngle: 35 * .pi / 180, endAngle: 145 * .pi / 180, clockwise: false)
        ctx.strokePath()
    }
    blip(ctx, CGPoint(x: 512, y: 600), cyan, 26)
    blip(ctx, CGPoint(x: 700, y: 800), red, 18)
    // Three server units.
    for (i, y) in ([430, 310, 190] as [CGFloat]).enumerated() {
        let unit = CGRect(x: 272, y: y, width: 480, height: 96)
        let path = CGPath(roundedRect: unit, cornerWidth: 22, cornerHeight: 22, transform: nil)
        ctx.addPath(path)
        ctx.setFillColor(CGColor(red: 0.09, green: 0.2, blue: 0.29, alpha: 1))
        ctx.fillPath()
        ctx.addPath(path)
        ctx.setStrokeColor(color(cyan, 0.55))
        ctx.setLineWidth(6)
        ctx.strokePath()
        for (j, led) in [green, cyan, i == 0 ? amber : green].enumerated() {
            let x = 318 + CGFloat(j) * 44
            ctx.setFillColor(led)
            ctx.fillEllipse(in: CGRect(x: x - 11, y: y + 37, width: 22, height: 22))
        }
        ctx.setStrokeColor(color(cyan, 0.4))
        ctx.setLineWidth(8)
        for k in 0..<2 {
            let ly = y + 34 + CGFloat(k) * 28
            ctx.move(to: CGPoint(x: 520, y: ly)); ctx.addLine(to: CGPoint(x: 712, y: ly))
        }
        ctx.strokePath()
    }
}

func render(_ draw: (CGContext) -> Void, size: Int, to url: URL) {
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.scaleBy(x: CGFloat(size) / 1024, y: CGFloat(size) / 1024)
    ctx.setShouldAntialias(true)
    draw(ctx)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
    CGImageDestinationFinalize(dest)
}

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
for (name, draw) in [("radarnebo", appIcon), ("radarnebo-server", serverIcon)] as [(String, (CGContext) -> Void)] {
    for size in [16, 32, 48, 64, 128, 256, 512, 1024] {
        render(draw, size: size, to: out.appendingPathComponent("\(name)-\(size).png"))
    }
}
