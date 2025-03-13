import Flutter
import UIKit
import StarIO
import StarIO_Extension

public class StartPrinterPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "start_printer", binaryMessenger: registrar.messenger())
        let instance = StartPrinterPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "portDiscovery":
            portDiscovery(call, result: result)
        case "checkStatus":
            checkStatus(call, result: result)
        case "print":
            print(call, result: result)
        case "connect":
            connect(call, result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func portDiscovery(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let interfaceType = arguments["type"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "Type is required", details: nil))
            return
        }
        
        DispatchQueue.global(qos: .background).async {
            do {
                var searchPrinterArray: [Dictionary<String, String>] = []
                
                if interfaceType == "LAN" || interfaceType == "All" {
                    let lanPrinters = SMPort.searchPrinter(target: "TCP:") as? [PortInfo] ?? []
                    searchPrinterArray.append(contentsOf: self.mapPrinterInfo(portInfos: lanPrinters))
                }
                
                if interfaceType == "Bluetooth" || interfaceType == "All" {
                    let btPrinters = SMPort.searchPrinter(target: "BT:") as? [PortInfo] ?? []
                    searchPrinterArray.append(contentsOf: self.mapPrinterInfo(portInfos: btPrinters))
                }
                
                if interfaceType == "USB" || interfaceType == "All" {
                    let usbPrinters = SMPort.searchPrinter(target: "USB:") as? [PortInfo] ?? []
                    searchPrinterArray.append(contentsOf: self.mapPrinterInfo(portInfos: usbPrinters))
                }
                
                DispatchQueue.main.async {
                    result(searchPrinterArray)
                }
            } catch {
                DispatchQueue.main.async {
                    result(FlutterError(code: "PORT_DISCOVERY_ERROR", message: error.localizedDescription, details: nil))
                }
            }
        }
    }
    
    private func mapPrinterInfo(portInfos: [PortInfo]) -> [[String: String]] {
        return portInfos.map { portInfo in
            var printerDict: [String: String] = [:]
            
            if portInfo.portName.starts(with: "BT:") {
                printerDict["portName"] = "BT:\(portInfo.macAddress)"
                
                if !portInfo.macAddress.isEmpty {
                    printerDict["macAddress"] = portInfo.macAddress
                    printerDict["modelName"] = portInfo.portName
                }
            } else {
                printerDict["portName"] = portInfo.portName
                
                if !portInfo.macAddress.isEmpty {
                    printerDict["macAddress"] = portInfo.macAddress
                    
                    if !portInfo.modelName.isEmpty {
                        printerDict["modelName"] = portInfo.modelName
                    }
                } else if portInfo.portName.starts(with: "USB:") {
                    if !portInfo.modelName.isEmpty {
                        printerDict["modelName"] = portInfo.modelName
                    }
                    
                    if let serialNumber = portInfo.USBSerialNumber {
                        printerDict["USBSerialNumber"] = serialNumber
                    }
                }
            }
            
            return printerDict
        }
    }
    
    private func checkStatus(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let portName = arguments["portName"] as? String,
              let emulation = arguments["emulation"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "Required parameters missing", details: nil))
            return
        }
        
        DispatchQueue.global(qos: .background).async {
            var port: SMPort?
            
            do {
                let portSettings = self.getPortSettings(emulation: emulation)
                port = try SMPort.getPort(portName: portName, portSettings: portSettings, ioTimeoutMillis: 10000)
                
                Thread.sleep(forTimeInterval: 0.5)
                
                let status = try port?.retrieveStatus()
                var response: [String: Any] = [:]
                
                response["is_success"] = true
                response["offline"] = status?.offline ?? false
                response["coverOpen"] = status?.coverOpen ?? false
                response["overTemp"] = status?.overTemp ?? false
                response["cutterError"] = status?.cutterError ?? false
                response["receiptPaperEmpty"] = status?.receiptPaperEmpty ?? false
                
                do {
                    if let firmwareInfo = try port?.getFirmwareInformation() {
                        response["ModelName"] = firmwareInfo["ModelName"]
                        response["FirmwareVersion"] = firmwareInfo["FirmwareVersion"]
                    }
                } catch {
                    response["error_message"] = error.localizedDescription
                }
                
                DispatchQueue.main.async {
                    result(response)
                }
            } catch {
                DispatchQueue.main.async {
                    result(FlutterError(code: "CHECK_STATUS_ERROR", message: error.localizedDescription, details: nil))
                }
            }
            
            if let p = port {
                SMPort.release(p)
            }
        }
    }
    
    private var starIoExtManager: StarIoExtManager?
    
    private func connect(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let portName = arguments["portName"] as? String,
              let emulation = arguments["emulation"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "Required parameters missing", details: nil))
            return
        }
        
        let hasBarcodeReader = arguments["hasBarcodeReader"] as? Bool ?? false
        
        DispatchQueue.global(qos: .background).async {
            do {
                let portSettings = self.getPortSettings(emulation: emulation)
                
                // Disconnect if already connected
                if self.starIoExtManager != nil {
                    self.starIoExtManager?.disconnect()
                }
                
                let managerType: StarIoExtManagerType = hasBarcodeReader ? .withBarcodeReader : .standard
                
                self.starIoExtManager = StarIoExtManager(type: managerType, portName: portName, portSettings: portSettings, ioTimeoutMillis: 10000)
                
                self.starIoExtManager?.connect { connectResult in
                    DispatchQueue.main.async {
                        switch connectResult {
                        case .success, .alreadyConnected:
                            result("Printer Connected")
                        default:
                            result(FlutterError(code: "CONNECT_ERROR", message: "Error Connecting to the printer", details: nil))
                        }
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    result(FlutterError(code: "CONNECT_ERROR", message: error.localizedDescription, details: nil))
                }
            }
        }
    }
    
    private func print(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let portName = arguments["portName"] as? String,
              let emulation = arguments["emulation"] as? String,
              let printCommands = arguments["printCommands"] as? [[String: Any]] else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "Required parameters missing", details: nil))
            return
        }
        
        if printCommands.isEmpty {
            let response: [String: Any] = [
                "offline": false,
                "coverOpen": false,
                "cutterError": false,
                "receiptPaperEmpty": false,
                "info_message": "No data to print",
                "is_success": true
            ]
            
            result(response)
            return
        }
        
        DispatchQueue.global(qos: .background).async {
            do {
                let builder = ICommandBuilder.init(starIoExtEmulation: self.getEmulation(emulation: emulation))
                builder.beginDocument()
                self.appendCommands(builder: builder, printCommands: printCommands)
                builder.endDocument()
                
                self.sendCommand(portName: portName, portSettings: self.getPortSettings(emulation: emulation), commands: builder.commands, result: result)
            } catch {
                DispatchQueue.main.async {
                    result(FlutterError(code: "PRINT_ERROR", message: error.localizedDescription, details: nil))
                }
            }
        }
    }
    
    private func appendCommands(builder: ICommandBuilder, printCommands: [[String: Any]]) {
        var encoding = String.Encoding.ascii
        
        for command in printCommands {
            // Character Space
            if let characterSpace = command["appendCharacterSpace"] as? Int {
                builder.appendCharacterSpace(characterSpace)
            }
            
            // Encoding
            else if let encodingStr = command["appendEncoding"] as? String {
                encoding = self.getEncoding(encoding: encodingStr)
            }
            
            // Code Page
            else if let codePage = command["appendCodePage"] as? String {
                builder.appendCodePage(self.getCodePageType(codePage: codePage))
            }
            
            // Basic Text
            else if let text = command["append"] as? String {
                builder.append(text.data(using: encoding)!)
            }
            
            // Raw Text
            else if let rawText = command["appendRaw"] as? String {
                builder.append(rawText.data(using: encoding)!)
            }
            
            // Multiple
            else if let multiple = command["appendMultiple"] as? String {
                let width = command["width"] as? Int ?? 2
                let height = command["height"] as? Int ?? 2
                builder.appendMultiple(multiple.data(using: encoding)!, width: width, height: height)
            }
            
            // Emphasis
            else if let emphasis = command["appendEmphasis"] as? String {
                builder.appendEmphasis(emphasis.data(using: encoding)!)
            }
            else if let enableEmphasis = command["enableEmphasis"] as? Bool {
                builder.appendEmphasis(enableEmphasis)
            }
            
            // Invert
            else if let invert = command["appendInvert"] as? String {
                builder.appendInvert(invert.data(using: encoding)!)
            }
            else if let enableInvert = command["enableInvert"] as? Bool {
                builder.appendInvert(enableInvert)
            }
            
            // Underline
            else if let underline = command["appendUnderline"] as? String {
                builder.appendUnderLine(underline.data(using: encoding)!)
            }
            else if let enableUnderline = command["enableUnderline"] as? Bool {
                builder.appendUnderLine(enableUnderline)
            }
            
            // International
            else if let international = command["appendInternational"] as? String {
                builder.appendInternational(self.getInternational(international: international))
            }
            
            // Line Feed
            else if let lineFeed = command["appendLineFeed"] as? Int {
                builder.appendLineFeed(lineFeed)
            }
            
            // Unit Feed
            else if let unitFeed = command["appendUnitFeed"] as? Int {
                builder.appendUnitFeed(unitFeed)
            }
            
            // Line Space
            else if let lineSpace = command["appendLineSpace"] as? Int {
                builder.appendLineSpace(lineSpace)
            }
            
            // Font Style
            else if let fontStyle = command["appendFontStyle"] as? String {
                builder.appendFontStyle(self.getFontStyle(fontStyle: fontStyle))
            }
            
            // Cut Paper
            else if let cutPaper = command["appendCutPaper"] as? String {
                builder.appendCutPaper(self.getCutPaperAction(action: cutPaper))
            }
            
            // Cash Drawer
            else if let openCashDrawer = command["openCashDrawer"] as? Int {
                builder.appendPeripheral(self.getPeripheralChannel(channel: openCashDrawer))
            }
            
            // Black Mark
            else if let blackMark = command["appendBlackMark"] as? String {
                builder.appendBlackMark(self.getBlackMarkType(type: blackMark))
            }
            
            // Raw Bytes
            else if let bytes = command["appendBytes"] as? String {
                builder.append(bytes.data(using: encoding)!)
            }
            else if let rawBytes = command["appendRawBytes"] as? String {
                builder.appendRaw(rawBytes.data(using: encoding)!)
            }
            
            // Absolute Position
            else if let absolutePosition = command["appendAbsolutePosition"] as? Int {
                if let data = command["data"] as? String {
                    builder.appendAbsolutePosition(data.data(using: encoding)!, position: absolutePosition)
                } else {
                    builder.appendAbsolutePosition(absolutePosition)
                }
            }
            
            // Alignment
            else if let alignment = command["appendAlignment"] as? String {
                if let data = command["data"] as? String {
                    builder.appendAlignment(data.data(using: encoding)!, position: self.getAlignment(alignment: alignment))
                } else {
                    builder.appendAlignment(self.getAlignment(alignment: alignment))
                }
            }
            
            // Horizontal Tab Position
            else if let tabPositions = command["appendHorizontalTabPosition"] as? [Int] {
                builder.appendHorizontalTabPosition(tabPositions)
            }
            
            // Logo
            else if let logo = command["appendLogo"] as? Int {
                let logoSizeStr = command["logoSize"] as? String ?? "Normal"
                let logoSize = self.getLogoSize(logoSize: logoSizeStr)
                builder.appendLogo(logoSize, number: logo)
            }
            
            // Barcode
            else if let barcode = command["appendBarcode"] as? String {
                let symbology = self.getBarcodeSymbology(symbology: command["BarcodeSymbology"] as? String ?? "Code128")
                let barcodeWidth = self.getBarcodeWidth(width: command["BarcodeWidth"] as? String ?? "Mode2")
                let height = command["height"] as? Int ?? 40
                let hri = command["hri"] as? Bool ?? true
                
                if let absolutePosition = command["absolutePosition"] as? Int {
                    builder.appendBarcodeWithAbsolutePosition(barcode.data(using: encoding)!, symbology: symbology, width: barcodeWidth, height: height, hri: hri, position: absolutePosition)
                } else if let alignmentStr = command["alignment"] as? String {
                    let alignment = self.getAlignment(alignment: alignmentStr)
                    builder.appendBarcodeWithAlignment(barcode.data(using: encoding)!, symbology: symbology, width: barcodeWidth, height: height, hri: hri, position: alignment)
                } else {
                    builder.appendBarcode(barcode.data(using: encoding)!, symbology: symbology, width: barcodeWidth, height: height, hri: hri)
                }
            }
            
            // Bitmap
            else if let bitmapPath = command["appendBitmap"] as? String {
                let diffusion = command["diffusion"] as? Bool ?? true
                let width = command["width"] as? Int ?? 576
                let bothScale = command["bothScale"] as? Bool ?? true
                let rotation = self.getBitmapConverterRotation(rotation: command["rotation"] as? String ?? "Normal")
                
                if let image = self.loadImage(path: bitmapPath) {
                    if let absolutePosition = command["absolutePosition"] as? Int {
                        builder.appendBitmapWithAbsolutePosition(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: absolutePosition)
                    } else if let alignmentStr = command["alignment"] as? String {
                        let alignment = self.getAlignment(alignment: alignmentStr)
                        builder.appendBitmapWithAlignment(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: alignment)
                    } else {
                        builder.appendBitmap(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation)
                    }
                }
            }
            
            // Bitmap Text
            else if let text = command["appendBitmapText"] as? String {
                let fontSize = CGFloat(command["fontSize"] as? Float ?? 25.0)
                let diffusion = command["diffusion"] as? Bool ?? true
                let width = command["width"] as? Int ?? 576
                let bothScale = command["bothScale"] as? Bool ?? true
                let rotation = self.getBitmapConverterRotation(rotation: command["rotation"] as? String ?? "Normal")
                
                if let image = self.createBitmapFromText(text: text, fontSize: fontSize, width: CGFloat(width)) {
                    if let absolutePosition = command["absolutePosition"] as? Int {
                        builder.appendBitmapWithAbsolutePosition(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: absolutePosition)
                    } else if let alignmentStr = command["alignment"] as? String {
                        let alignment = self.getAlignment(alignment: alignmentStr)
                        builder.appendBitmapWithAlignment(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: alignment)
                    } else {
                        builder.appendBitmap(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation)
                    }
                }
            }
            
            // Bitmap Byte Array
            else if let byteArray = command["appendBitmapByteArray"] as? FlutterStandardTypedData {
                let diffusion = command["diffusion"] as? Bool ?? true
                let width = command["width"] as? Int ?? 576
                let bothScale = command["bothScale"] as? Bool ?? true
                let rotation = self.getBitmapConverterRotation(rotation: command["rotation"] as? String ?? "Normal")
                
                if let image = UIImage(data: byteArray.data) {
                    if let absolutePosition = command["absolutePosition"] as? Int {
                        builder.appendBitmapWithAbsolutePosition(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: absolutePosition)
                    } else if let alignmentStr = command["alignment"] as? String {
                        let alignment = self.getAlignment(alignment: alignmentStr)
                        builder.appendBitmapWithAlignment(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation, position: alignment)
                    } else {
                        builder.appendBitmap(image, diffusion: diffusion, width: width, bothScale: bothScale, rotation: rotation)
                    }
                }
            }
        }
    }
    
    private func loadImage(path: String) -> UIImage? {
        if path.starts(with: "http://") || path.starts(with: "https://") {
            if let url = URL(string: path), let data = try? Data(contentsOf: url) {
                return UIImage(data: data)
            }
        } else {
            return UIImage(contentsOfFile: path)
        }
        return nil
    }
    
    private func createBitmapFromText(text: String, fontSize: CGFloat, width: CGFloat) -> UIImage? {
        let font = UIFont.monospacedDigitSystemFont(ofSize: fontSize, weight: .regular)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.black
        ]
        
        let attributedString = NSAttributedString(string: text, attributes: attributes)
        let stringSize = attributedString.size()
        
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: min(width, stringSize.width), height: stringSize.height))
        return renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: renderer.format.bounds.size))
            attributedString.draw(at: .zero)
        }
    }
    
    private func sendCommand(portName: String, portSettings: String, commands: Data, result: @escaping FlutterResult) {
        var port: SMPort?
        
        do {
            port = try SMPort.getPort(portName: portName, portSettings: portSettings, ioTimeoutMillis: 10000)
            Thread.sleep(forTimeInterval: 0.1)
            
            guard let printerStatus = try port?.beginCheckedBlock() else {
                result(FlutterError(code: "PRINT_ERROR", message: "Failed to get printer status", details: nil))
                return
            }
            
            if !self.validatePrinterStatus(status: printerStatus) {
                self.sendPrinterResponse(status: printerStatus, isSuccess: false, errorMessage: self.getPrinterError(status: printerStatus), result: result)
                return
            }
            
            try port?.write(commands, offset: 0, size: commands.count)
            port?.setEndCheckedBlockTimeoutMillis(30000)
            let finalStatus = try port?.endCheckedBlock()
            
            self.sendPrinterResponse(status: finalStatus, isSuccess: true, errorMessage: nil, result: result)
        } catch {
            DispatchQueue.main.async {
                result(FlutterError(code: "PRINT_ERROR", message: error.localizedDescription, details: nil))
            }
        }
        
        if let p = port {
            SMPort.release(p)
        }
    }
    
    private func validatePrinterStatus(status: StarPrinterStatus_2) -> Bool {
        return !status.offline && !status.coverOpen && !status.receiptPaperEmpty && !status.presenterPaperJamError
    }
    
    private func getPrinterError(status: StarPrinterStatus_2?) -> String? {
        guard let status = status else { return "Unknown error" }
        
        if status.offline { return "Printer is offline" }
        if status.coverOpen { return "Printer cover is open" }
        if status.receiptPaperEmpty { return "Paper empty" }
        if status.presenterPaperJamError { return "Paper jam" }
        
        return nil
    }
    
    private func sendPrinterResponse(status: StarPrinterStatus_2?, isSuccess: Bool, errorMessage: String?, result: @escaping FlutterResult) {
        let response: [String: Any] = [
            "offline": status?.offline ?? false,
            "coverOpen": status?.coverOpen ?? false,
            "cutterError": status?.cutterError ?? false,
            "receiptPaperEmpty": status?.receiptPaperEmpty ?? false,
            "is_success": isSuccess,
            "error_message": errorMessage ?? NSNull()
        ]
        
        DispatchQueue.main.async {
            result(response)
        }
    }
    
    // Helper methods to convert string parameters to appropriate enum types
    
    private func getPortSettings(emulation: String) -> String {
        switch emulation {
        case "EscPosMobile":
            return "mini"
        case "EscPos":
            return "escpos"
        case "StarPRNT", "StarPRNTL":
            return "Portable;l"
        default:
            return emulation
        }
    }
    
    private func getEmulation(emulation: String) -> StarIoExtEmulation {
        switch emulation {
        case "StarPRNT":
            return .starPRNT
        case "StarPRNTL":
            return .starPRNTL
        case "StarLine":
            return .starLine
        case "StarGraphic":
            return .starGraphic
        case "EscPos":
            return .escPos
        case "EscPosMobile":
            return .escPosMobile
        case "StarDotImpact":
            return .starDotImpact
        default:
            return .starLine
        }
    }
    
    private func getCutPaperAction(action: String) -> SCBCutPaperAction {
        switch action {
        case "FullCut":
            return .fullCut
        case "FullCutWithFeed":
            return .fullCutWithFeed
        case "PartialCut":
            return .partialCut
        case "PartialCutWithFeed":
            return .partialCutWithFeed
        default:
            return .partialCutWithFeed
        }
    }
    
    private func getAlignment(alignment: String) -> SCBAlignmentPosition {
        switch alignment {
        case "Left":
            return .left
        case "Center":
            return .center
        case "Right":
            return .right
        default:
            return .left
        }
    }
    
    private func getBitmapConverterRotation(rotation: String) -> SCBBitmapConverterRotation {
        switch rotation {
        case "Left90":
            return .left90
        case "Right90":
            return .right90
        case "Rotate180":
            return .rotate180
        default:
            return .normal
        }
    }
    
    private func getBarcodeSymbology(symbology: String) -> SCBBarcodeSymbology {
        switch symbology {
        case "Code39":
            return .code39
        case "Code93":
            return .code93
        case "ITF":
            return .ITF
        case "JAN8":
            return .JAN8
        case "JAN13":
            return .JAN13
        case "NW7":
            return .NW7
        case "UPCA":
            return .UPCA
        case "UPCE":
            return .UPCE
        default:
            return .code128
        }
    }
    
    private func getBarcodeWidth(width: String) -> SCBBarcodeWidth {
        switch width {
        case "Mode1":
            return .mode1
        case "Mode3":
            return .mode3
        case "Mode4":
            return .mode4
        case "Mode5":
            return .mode5
        case "Mode6":
            return .mode6
        case "Mode7":
            return .mode7
        case "Mode8":
            return .mode8
        case "Mode9":
            return .mode9
        default:
            return .mode2
        }
    }
    
    private func getInternational(international: String) -> SCBInternationalType {
        switch international {
        case "UK":
            return .UK
        case "France":
            return .france
        case "Germany":
            return .germany
        case "Denmark":
            return .denmark
        case "Sweden":
            return .sweden
        case "Italy":
            return .italy
        case "Spain":
            return .spain
        case "Japan":
            return .japan
        case "Norway":
            return .norway
        case "Denmark2":
            return .denmark2
        case "Spain2":
            return .spain2
        case "LatinAmerica":
            return .latinAmerica
        case "Korea":
            return .korea
        case "Ireland":
            return .ireland
        case "Legal":
            return .legal
        default:
            return .USA
        }
    }
    
    private func getFontStyle(fontStyle: String) -> SCBFontStyleType {
        switch fontStyle {
        case "A":
            return .A
        case "B":
            return .B
        default:
            return .A
        }
    }
    
    private func getPeripheralChannel(channel: Int) -> SCBPeripheralChannel {
        switch channel {
        case 1:
            return .no1
        case 2:
            return .no2
        default:
            return .no1
        }
    }
    
    private func getBlackMarkType(type: String) -> SCBBlackMarkType {
        switch type {
        case "Valid":
            return .valid
        case "Invalid":
            return .invalid
        case "ValidWithDetection":
            return .validWithDetection
        default:
            return .valid
        }
    }
    
    private func getLogoSize(logoSize: String) -> SCBLogoSize {
        switch logoSize {
        case "Normal":
            return .normal
        case "DoubleWidth":
            return .doubleWidth
        case "DoubleHeight":
            return .doubleHeight
        case "DoubleWidthDoubleHeight":
            return .doubleWidthDoubleHeight
        default:
            return .normal
        }
    }
    
    private func getCodePageType(codePage: String) -> SCBCodePageType {
        switch codePage {
        case "CP437":
            return .CP437
        case "CP737":
            return .CP737
        case "CP772":
            return .CP772
        case "CP774":
            return .CP774
        case "CP851":
            return .CP851
        case "CP852":
            return .CP852
        case "CP855":
            return .CP855
        case "CP857":
            return .CP857
        case "CP858":
            return .CP858
        case "CP860":
            return .CP860
        case "CP861":
            return .CP861
        case "CP862":
            return .CP862
        case "CP863":
            return .CP863
        case "CP864":
            return .CP864
        case "CP865":
            return .CP865
        case "CP869":
            return .CP869
        case "CP874":
            return .CP874
        case "CP928":
            return .CP928
        case "CP932":
            return .CP932
        case "CP999":
            return .CP999
        case "CP1001":
            return .CP1001
        case "CP1250":
            return .CP1250
        case "CP1251":
            return .CP1251
        case "CP1252":
            return .CP1252
        case "CP2001":
            return .CP2001
        case "CP3001":
            return .CP3001
        case "CP3002":
            return .CP3002
        case "CP3011":
            return .CP3011
        case "CP3012":
            return .CP3012
        case "CP3021":
            return .CP3021
        case "CP3041":
            return .CP3041
        case "CP3840":
            return .CP3840
        case "CP3841":
            return .CP3841
        case "CP3843":
            return .CP3843
        case "CP3845":
            return .CP3845
        case "CP3846":
            return .CP3846
        case "CP3847":
            return .CP3847
        case "CP3848":
            return .CP3848
        case "UTF8":
            return .UTF8
        case "Blank":
            return .blank
        default:
            return .CP998
        }
    }
    
    private func getEncoding(encoding: String) -> String.Encoding {
        switch encoding {
        case "Windows-1252":
            return .windowsCP1252
        case "Shift-JIS":
            if let _ = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.shiftJIS.rawValue))) {
                return .shiftJIS
            }
            return .utf8
        case "Windows-1251":
            if let _ = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.windowsCyrillic.rawValue))) {
                return .windowsCP1251
            }
            return .utf8
        case "GB2312":
            if let _ = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))) {
                return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
            }
            return .utf8
        case "Big5":
            if let _ = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.big5.rawValue))) {
                return .big5
            }
            return .utf8
        case "UTF-8":
            return .utf8
        default:
            return .ascii
        }
    }
}