// Zeichnet das iOS-App-Symbol: die Strichliste vom Bierdeckel, weiß auf Tinte.
// Dasselbe Zeichen wie androidApp/.../ic_launcher_foreground.xml und das Favicon der Verwaltung
// (24er-Raster, Strich 1,6). Aufruf (das Interpretieren mit `swift` stürzt unter Xcode 27 ab):
//   xcrun swiftc docs/tools/gen_app_icon.swift -o /tmp/genicon && /tmp/genicon iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.setFillColor(CGColor(srgbRed: 0x14 / 255.0, green: 0x14 / 255.0, blue: 0x14 / 255.0, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

// Das 24er-Raster auf 1024 Pixel, das Zeichen mittig. y läuft in CoreGraphics nach oben.
let s = Double(size)
let unit = s / 24.0 * 0.8
let origin = (s - 24.0 * unit) / 2.0
func p(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: origin + x * unit, y: s - (origin + y * unit)) }
for x in [7.5, 10.5, 13.5, 16.5] { ctx.move(to: p(x, 8)); ctx.addLine(to: p(x, 16)) }
ctx.move(to: p(6, 14.8)); ctx.addLine(to: p(18, 9.2))
ctx.setLineWidth(1.6 * unit)
ctx.setLineCap(.round)
ctx.setStrokeColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
ctx.strokePath()

let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: out) as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("PNG nicht geschrieben: \(out)") }
