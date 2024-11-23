package com.phonetechbd.start_printer


import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.webkit.URLUtil
import androidx.annotation.NonNull
import com.starmicronics.stario.PortInfo
import com.starmicronics.stario.StarIOPort
import com.starmicronics.starioextension.ICommandBuilder.FontStyleType
import com.starmicronics.starioextension.ICommandBuilder.PeripheralChannel
import com.starmicronics.starioextension.ICommandBuilder.BlackMarkType
import com.starmicronics.starioextension.ICommandBuilder.LogoSize
import com.starmicronics.stario.StarPrinterStatus
import com.starmicronics.starioextension.ICommandBuilder
import com.starmicronics.starioextension.ICommandBuilder.*
import com.starmicronics.starioextension.IConnectionCallback
import com.starmicronics.starioextension.StarIoExt
import com.starmicronics.starioextension.StarIoExt.Emulation
import com.starmicronics.starioextension.StarIoExtManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException

class StartPrinterPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var starIoExtManager: StarIoExtManager? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "start_printer")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
        val result = MethodResultWrapper(rawResult)
        Thread(MethodRunner(call, result)).start()
    }

    inner class MethodRunner(private val call: MethodCall, private val result: Result) : Runnable {
        override fun run() {
            when (call.method) {
                "portDiscovery" -> portDiscovery(call, result)
                "checkStatus" -> checkStatus(call, result)
                "print" -> print(call, result)
                "connect" -> connect(call, result)
                else -> result.notImplemented()
            }
        }
    }

    class MethodResultWrapper(private val methodResult: Result) : Result {
        private val handler = Handler(Looper.getMainLooper())

        override fun success(result: Any?) {
            handler.post { methodResult.success(result) }
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            handler.post { methodResult.error(errorCode, errorMessage, errorDetails) }
        }

        override fun notImplemented() {
            handler.post { methodResult.notImplemented() }
        }
    }

    private fun portDiscovery(call: MethodCall, result: Result) {
        try {
            val interfaceType = call.argument<String>("type") ?: "All"
            val portList = mutableListOf<Map<String, String>>()

            when (interfaceType) {
                "LAN", "All" -> portList.addAll(searchPrinters("TCP:"))
                "Bluetooth", "All" -> portList.addAll(searchPrinters("BT:"))
                "USB", "All" -> portList.addAll(searchPrinters("USB:"))
            }

            result.success(portList)
        } catch (e: Exception) {
            result.error("PORT_DISCOVERY_ERROR", e.message, null)
        }
    }

    private fun searchPrinters(interfaceType: String): List<Map<String, String>> {
        return StarIOPort.searchPrinter(interfaceType, context).map { portInfo ->
        mutableMapOf<String, String>().apply {
            put("portName", when {
                portInfo.portName.startsWith("BT:") -> "BT:${portInfo.macAddress}"
                else -> portInfo.portName
            })

            if (portInfo.macAddress.isNotEmpty()) {
                put("macAddress", portInfo.macAddress)
                if (portInfo.portName.startsWith("BT:")) {
                    put("modelName", portInfo.portName)
                } else if (portInfo.modelName.isNotEmpty()) {
                    put("modelName", portInfo.modelName)
                }
            } else if (interfaceType == "USB") {
                if (portInfo.modelName.isNotEmpty()) {
                    put("modelName", portInfo.modelName)
                }
                // Get serial number from portInfo
                val serialNumber = portInfo.getPortName().substringAfter("USB:")
                if (serialNumber.isNotEmpty()) {
                    put("USBSerialNumber", serialNumber)
                }
            }
        }
    }
    }

    private fun checkStatus(call: MethodCall, result: Result) {
        val portName = call.argument<String>("portName") ?: return
        val emulation = call.argument<String>("emulation") ?: return
        var port: StarIOPort? = null

        try {
            val portSettings = getPortSettings(emulation)
            port = StarIOPort.getPort(portName, portSettings, 10000, context)
            Thread.sleep(500)

            val status = port.retreiveStatus()
            val response = mutableMapOf<String, Any?>().apply {
                put("is_success", true)
                put("offline", status.offline)
                put("coverOpen", status.coverOpen)
                put("overTemp", status.overTemp)
                put("cutterError", status.cutterError)
                put("receiptPaperEmpty", status.receiptPaperEmpty)

                try {
                    val firmware = port.firmwareInformation
                    put("ModelName", firmware["ModelName"])
                    put("FirmwareVersion", firmware["FirmwareVersion"])
                } catch (e: Exception) {
                    put("error_message", e.message)
                }
            }

            result.success(response)
        } catch (e: Exception) {
            result.error("CHECK_STATUS_ERROR", e.message, null)
        } finally {
            try {
                port?.let { StarIOPort.releasePort(it) }
            } catch (e: Exception) {
                result.error("CHECK_STATUS_ERROR", e.message, null)
            }
        }
    }

    private fun connect(call: MethodCall, result: Result) {
        val portName = call.argument<String>("portName") ?: return
        val emulation = call.argument<String>("emulation") ?: return
        val hasBarcodeReader = call.argument<Boolean>("hasBarcodeReader") ?: false

        try {
            val portSettings = getPortSettings(emulation)
            
            starIoExtManager?.let { manager ->
                if (manager.port != null) {
                    manager.disconnect(object : IConnectionCallback {
                        override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {}
                        override fun onDisconnected() {}
                    })
                }
            }

            starIoExtManager = StarIoExtManager(
                if (hasBarcodeReader) StarIoExtManager.Type.WithBarcodeReader
                else StarIoExtManager.Type.Standard,
                portName,
                portSettings,
                10000,
                context
            ).apply {
                connect(object : IConnectionCallback {
                    override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {
                        when (connectResult) {
                            IConnectionCallback.ConnectResult.Success,
                            IConnectionCallback.ConnectResult.AlreadyConnected -> {
                                result.success("Printer Connected")
                            }
                            else -> {
                                result.error("CONNECT_ERROR", "Error Connecting to the printer", null)
                            }
                        }
                    }

                    override fun onDisconnected() {
                        // Optional: Handle disconnect event
                    }
                })
            }
        } catch (e: Exception) {
            result.error("CONNECT_ERROR", e.message, null)
        }
    }

    private fun print(call: MethodCall, result: Result) {
        val portName = call.argument<String>("portName") ?: return
        val emulation = call.argument<String>("emulation") ?: return
        val printCommands = call.argument<List<Map<String, Any>>>("printCommands") ?: return

        if (printCommands.isEmpty()) {
            result.success(mapOf(
                "offline" to false,
                "coverOpen" to false,
                "cutterError" to false,
                "receiptPaperEmpty" to false,
                "info_message" to "No data to print",
                "is_success" to true
            ))
            return
        }

        try {
            val builder = StarIoExt.createCommandBuilder(getEmulation(emulation))
            builder.beginDocument()
            appendCommands(builder, printCommands)
            builder.endDocument()

            sendCommand(portName, getPortSettings(emulation), builder.commands, result)
        } catch (e: Exception) {
            result.error("PRINT_ERROR", e.message, null)
        }
    }


private fun appendCommands(builder: ICommandBuilder, printCommands: List<Map<String, Any>>) {
    var encoding = Charset.forName("US-ASCII")

    printCommands.forEach { command ->
        when {
            // Character Space
            command["appendCharacterSpace"] != null -> {
                builder.appendCharacterSpace(command["appendCharacterSpace"].toString().toInt())
            }
            
            // Encoding
            command["appendEncoding"] != null -> {
                encoding = getEncoding(command["appendEncoding"].toString())
            }
            
            // Code Page
            command["appendCodePage"] != null -> {
                builder.appendCodePage(getCodePageType(command["appendCodePage"].toString()))
            }
            
            // Basic Text
            command["append"] != null -> {
                builder.append(command["append"].toString().toByteArray(encoding))
            }
            
            // Raw Text
            command["appendRaw"] != null -> {
                builder.append(command["appendRaw"].toString().toByteArray(encoding))
            }
            
            // Multiple
            command["appendMultiple"] != null -> {
                builder.appendMultiple(
                    command["appendMultiple"].toString().toByteArray(encoding),
                    command["width"] as? Int ?: 2,
                    command["height"] as? Int ?: 2
                )
            }
            
            // Emphasis
            command["appendEmphasis"] != null -> {
                builder.appendEmphasis(command["appendEmphasis"].toString().toByteArray(encoding))
            }
            command["enableEmphasis"] != null -> {
                builder.appendEmphasis(command["enableEmphasis"].toString().toBoolean())
            }
            
            // Invert
            command["appendInvert"] != null -> {
                builder.appendInvert(command["appendInvert"].toString().toByteArray(encoding))
            }
            command["enableInvert"] != null -> {
                builder.appendInvert(command["enableInvert"].toString().toBoolean())
            }
            
            // Underline
            command["appendUnderline"] != null -> {
                builder.appendUnderLine(command["appendUnderline"].toString().toByteArray(encoding))
            }
            command["enableUnderline"] != null -> {
                builder.appendUnderLine(command["enableUnderline"].toString().toBoolean())
            }
            
            // International
            command["appendInternational"] != null -> {
                builder.appendInternational(getInternational(command["appendInternational"].toString()))
            }
            
            // Line Feed
            command["appendLineFeed"] != null -> {
                builder.appendLineFeed(command["appendLineFeed"] as Int)
            }
            
            // Unit Feed
            command["appendUnitFeed"] != null -> {
                builder.appendUnitFeed(command["appendUnitFeed"] as Int)
            }
            
            // Line Space
            command["appendLineSpace"] != null -> {
                builder.appendLineSpace(command["appendLineSpace"] as Int)
            }
            
            // Font Style
            command["appendFontStyle"] != null -> {
                builder.appendFontStyle(getFontStyle(command["appendFontStyle"].toString()))
            }
            
            // Cut Paper
            command["appendCutPaper"] != null -> {
                builder.appendCutPaper(getCutPaperAction(command["appendCutPaper"].toString()))
            }
            
            // Cash Drawer
            command["openCashDrawer"] != null -> {
                builder.appendPeripheral(getPeripheralChannel(command["openCashDrawer"] as Int))
            }
            
            // Black Mark
            command["appendBlackMark"] != null -> {
                builder.appendBlackMark(getBlackMarkType(command["appendBlackMark"].toString()))
            }
            
            // Raw Bytes
            command["appendBytes"] != null -> {
                builder.append(command["appendBytes"].toString().toByteArray(encoding))
            }
            command["appendRawBytes"] != null -> {
                builder.appendRaw(command["appendRawBytes"].toString().toByteArray(encoding))
            }
            
            // Absolute Position
            command["appendAbsolutePosition"] != null -> {
                if (command["data"] != null) {
                    builder.appendAbsolutePosition(
                        command["data"].toString().toByteArray(encoding),
                        command["appendAbsolutePosition"].toString().toInt()
                    )
                } else {
                    builder.appendAbsolutePosition(command["appendAbsolutePosition"].toString().toInt())
                }
            }
            
            // Alignment
            command["appendAlignment"] != null -> {
                if (command["data"] != null) {
                    builder.appendAlignment(
                        command["data"].toString().toByteArray(encoding),
                        getAlignment(command["appendAlignment"].toString())
                    )
                } else {
                    builder.appendAlignment(getAlignment(command["appendAlignment"].toString()))
                }
            }
            
            // Horizontal Tab Position
            command["appendHorizontalTabPosition"] != null -> {
                (command["appendHorizontalTabPosition"] as? IntArray)?.let {
                    builder.appendHorizontalTabPosition(it)
                }
            }
            
            // Logo
            command["appendLogo"] != null -> {
                val logoSize = command["logoSize"]?.toString()?.let { getLogoSize(it) } 
                    ?: getLogoSize("Normal")
                builder.appendLogo(logoSize, command["appendLogo"] as Int)
            }
            
            // Barcode
            command["appendBarcode"] != null -> {
                val barcodeSymbology = command["BarcodeSymbology"]?.toString()?.let { 
                    getBarcodeSymbology(it) 
                } ?: getBarcodeSymbology("Code128")
                
                val barcodeWidth = command["BarcodeWidth"]?.toString()?.let { 
                    getBarcodeWidth(it) 
                } ?: getBarcodeWidth("Mode2")
                
                val height = command["height"]?.toString()?.toInt() ?: 40
                val hri = command["hri"]?.toString()?.toBoolean() ?: true

                when {
                    command["absolutePosition"] != null -> {
                        builder.appendBarcodeWithAbsolutePosition(
                            command["appendBarcode"].toString().toByteArray(encoding),
                            barcodeSymbology,
                            barcodeWidth,
                            height,
                            hri,
                            command["absolutePosition"] as Int
                        )
                    }
                    command["alignment"] != null -> {
                        builder.appendBarcodeWithAlignment(
                            command["appendBarcode"].toString().toByteArray(encoding),
                            barcodeSymbology,
                            barcodeWidth,
                            height,
                            hri,
                            getAlignment(command["alignment"].toString())
                        )
                    }
                    else -> {
                        builder.appendBarcode(
                            command["appendBarcode"].toString().toByteArray(encoding),
                            barcodeSymbology,
                            barcodeWidth,
                            height,
                            hri
                        )
                    }
                }
            }
            
            // Bitmap
            command["appendBitmap"] != null -> {
                val diffusion = command["diffusion"]?.toString()?.toBoolean() ?: true
                val width = command["width"]?.toString()?.toInt() ?: 576
                val bothScale = command["bothScale"]?.toString()?.toBoolean() ?: true
                val rotation = command["rotation"]?.toString()?.let { 
                    getConverterRotation(it) 
                } ?: BitmapConverterRotation.Normal

                try {
                    val bitmap = when {
                        URLUtil.isValidUrl(command["appendBitmap"].toString()) -> {
                            val uri = Uri.parse(command["appendBitmap"].toString())
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                        else -> BitmapFactory.decodeFile(command["appendBitmap"].toString())
                    }

                    bitmap?.let {
                        when {
                            command["absolutePosition"] != null -> {
                                builder.appendBitmapWithAbsolutePosition(
                                    bitmap,
                                    diffusion,
                                    width,
                                    bothScale,
                                    rotation,
                                    command["absolutePosition"].toString().toInt()
                                )
                            }
                            command["alignment"] != null -> {
                                builder.appendBitmapWithAlignment(
                                    bitmap,
                                    diffusion,
                                    width,
                                    bothScale,
                                    rotation,
                                    getAlignment(command["alignment"].toString())
                                )
                            }
                            else -> {
                                builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FlutterStarPrnt", "Error processing bitmap", e)
                }
            }
            
            // Bitmap Text
            command["appendBitmapText"] != null -> {
                val fontSize = command["fontSize"]?.toString()?.toFloat() ?: 25f
                val diffusion = command["diffusion"]?.toString()?.toBoolean() ?: true
                val width = command["width"]?.toString()?.toInt() ?: 576
                val bothScale = command["bothScale"]?.toString()?.toBoolean() ?: true
                val text = command["appendBitmapText"].toString()
                val typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                val bitmap = createBitmapFromText(text, fontSize, width, typeface)
                val rotation = command["rotation"]?.toString()?.let { 
                    getConverterRotation(it) 
                } ?: BitmapConverterRotation.Normal

                when {
                    command["absolutePosition"] != null -> {
                        builder.appendBitmapWithAbsolutePosition(
                            bitmap,
                            diffusion,
                            width,
                            bothScale,
                            rotation,
                            command["absolutePosition"].toString().toInt()
                        )
                    }
                    command["alignment"] != null -> {
                        builder.appendBitmapWithAlignment(
                            bitmap,
                            diffusion,
                            width,
                            bothScale,
                            rotation,
                            getAlignment(command["alignment"].toString())
                        )
                    }
                    else -> {
                        builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                    }
                }
            }

            // Bitmap Byte Array
            command["appendBitmapByteArray"] != null -> {
                val diffusion = command["diffusion"]?.toString()?.toBoolean() ?: true
                val width = command["width"]?.toString()?.toInt() ?: 576
                val bothScale = command["bothScale"]?.toString()?.toBoolean() ?: true
                val rotation = command["rotation"]?.toString()?.let { 
                    getConverterRotation(it) 
                } ?: BitmapConverterRotation.Normal

                try {
                    val byteArray = command["appendBitmapByteArray"] as ByteArray
                    val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    
                    bitmap?.let {
                        when {
                            command["absolutePosition"] != null -> {
                                builder.appendBitmapWithAbsolutePosition(
                                    bitmap,
                                    diffusion,
                                    width,
                                    bothScale,
                                    rotation,
                                    command["absolutePosition"].toString().toInt()
                                )
                            }
                            command["alignment"] != null -> {
                                builder.appendBitmapWithAlignment(
                                    bitmap,
                                    diffusion,
                                    width,
                                    bothScale,
                                    rotation,
                                    getAlignment(command["alignment"].toString())
                                )
                            }
                            else -> {
                                builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FlutterStarPrnt", "Error processing bitmap byte array", e)
                }
            }
        }
    }
}


    private fun processBitmapCommand(builder: ICommandBuilder, command: Map<String, Any>) {
        try {
            val bitmapPath = command["appendBitmap"].toString()
            val bitmap = when {
                URLUtil.isValidUrl(bitmapPath) -> {
                    val uri = Uri.parse(bitmapPath)
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                else -> BitmapFactory.decodeFile(bitmapPath)
            }

            bitmap?.let {
                val diffusion = command["diffusion"] as? Boolean ?: true
                val width = command["width"] as? Int ?: 576
                val bothScale = command["bothScale"] as? Boolean ?: true
                val rotation = getConverterRotation(command["rotation"]?.toString() ?: "Normal")

                when {
                    command["absolutePosition"] != null -> {
                        builder.appendBitmapWithAbsolutePosition(
                            bitmap, diffusion, width, bothScale, rotation,
                            command["absolutePosition"].toString().toInt()
                        )
                    }
                    command["alignment"] != null -> {
                        builder.appendBitmapWithAlignment(
                            bitmap, diffusion, width, bothScale, rotation,
                            getAlignment(command["alignment"].toString())
                        )
                    }
                    else -> {
                        builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StarPrnt", "Error processing bitmap", e)
        }
    }

private fun getFontStyle(fontStyle: String): FontStyleType {
    return when (fontStyle) {
        "A" -> FontStyleType.A
        "B" -> FontStyleType.B
        else -> FontStyleType.A
    }
}

private fun getPeripheralChannel(peripheralChannel: Int): PeripheralChannel {
    return when (peripheralChannel) {
        1 -> PeripheralChannel.No1
        2 -> PeripheralChannel.No2
        else -> PeripheralChannel.No1
    }
}

private fun getBlackMarkType(blackMarkType: String): BlackMarkType {
    return when (blackMarkType) {
        "Valid" -> BlackMarkType.Valid
        "Invalid" -> BlackMarkType.Invalid
        "ValidWithDetection" -> BlackMarkType.ValidWithDetection
        else -> BlackMarkType.Valid
    }
}

private fun getLogoSize(logoSize: String): LogoSize {
    return when (logoSize) {
        "Normal" -> LogoSize.Normal
        "DoubleWidth" -> LogoSize.DoubleWidth
        "DoubleHeight" -> LogoSize.DoubleHeight
        "DoubleWidthDoubleHeight" -> LogoSize.DoubleWidthDoubleHeight
        else -> LogoSize.Normal
    }
}
    private fun sendCommand(portName: String, portSettings: String, commands: ByteArray, result: Result) {
        var port: StarIOPort? = null
        try {
            port = StarIOPort.getPort(portName, portSettings, 10000, context)
            Thread.sleep(100)

            var status = port.beginCheckedBlock()
            val response = mutableMapOf<String, Any?>()
            
            if (!validatePrinterStatus(status)) {
                sendPrinterResponse(status, false, getPrinterError(status), result)
                return
            }

            port.writePort(commands, 0, commands.size)
            port.setEndCheckedBlockTimeoutMillis(30000)
            status = port.endCheckedBlock()

            sendPrinterResponse(status, true, null, result)
        } catch (e: Exception) {
            result.error("PRINT_ERROR", e.message, null)
        } finally {
            try {
                port?.let { StarIOPort.releasePort(it) }
            } catch (e: Exception) {
                Log.e("StarPrnt", "Error releasing port", e)
            }
        }
    }

    private fun validatePrinterStatus(status: StarPrinterStatus): Boolean {
        return !status.offline && !status.coverOpen && !status.receiptPaperEmpty && !status.presenterPaperJamError
    }

    private fun getPrinterError(status: StarPrinterStatus): String? = when {
        status.offline -> "Printer is offline"
        status.coverOpen -> "Printer cover is open"
        status.receiptPaperEmpty -> "Paper empty"
        status.presenterPaperJamError -> "Paper jam"
        else -> null
    }

    private fun sendPrinterResponse(status: StarPrinterStatus, isSuccess: Boolean, errorMessage: String?, result: Result) {
        val response = mutableMapOf<String, Any?>().apply {
            put("offline", status.offline)
            put("coverOpen", status.coverOpen)
            put("cutterError", status.cutterError)
            put("receiptPaperEmpty", status.receiptPaperEmpty)
            put("is_success", isSuccess)
            errorMessage?.let { put("error_message", it) }
        }
        result.success(response)
    }

    private fun getPortSettings(emulation: String): String = when (emulation) {
        "EscPosMobile" -> "mini"
        "EscPos" -> "escpos"
        "StarPRNT", "StarPRNTL" -> "Portable;l"
        else -> emulation
    }

    private fun getEmulation(emulation: String): Emulation = when (emulation) {
        "StarPRNT" -> Emulation.StarPRNT
        "StarPRNTL" -> Emulation.StarPRNTL
        "StarLine" -> Emulation.StarLine
        "StarGraphic" -> Emulation.StarGraphic
        "EscPos" -> Emulation.EscPos
        "EscPosMobile" -> Emulation.EscPosMobile
        "StarDotImpact" -> Emulation.StarDotImpact
        else -> Emulation.StarLine
    }
private fun getCutPaperAction(action: String): CutPaperAction = when (action) {
        "FullCut" -> CutPaperAction.FullCut
        "FullCutWithFeed" -> CutPaperAction.FullCutWithFeed
        "PartialCut" -> CutPaperAction.PartialCut
        "PartialCutWithFeed" -> CutPaperAction.PartialCutWithFeed
        else -> CutPaperAction.PartialCutWithFeed
    }

    private fun getAlignment(alignment: String): AlignmentPosition = when (alignment) {
        "Left" -> AlignmentPosition.Left
        "Center" -> AlignmentPosition.Center
        "Right" -> AlignmentPosition.Right
        else -> AlignmentPosition.Left
    }

    private fun getConverterRotation(rotation: String): BitmapConverterRotation = when (rotation) {
        "Left90" -> BitmapConverterRotation.Left90
        "Right90" -> BitmapConverterRotation.Right90
        "Rotate180" -> BitmapConverterRotation.Rotate180
        else -> BitmapConverterRotation.Normal
    }

    private fun getBarcodeSymbology(symbology: String): BarcodeSymbology = when (symbology) {
        "Code39" -> BarcodeSymbology.Code39
        "Code93" -> BarcodeSymbology.Code93
        "ITF" -> BarcodeSymbology.ITF
        "JAN8" -> BarcodeSymbology.JAN8
        "JAN13" -> BarcodeSymbology.JAN13
        "NW7" -> BarcodeSymbology.NW7
        "UPCA" -> BarcodeSymbology.UPCA
        "UPCE" -> BarcodeSymbology.UPCE
        else -> BarcodeSymbology.Code128
    }

    private fun getBarcodeWidth(width: String): BarcodeWidth = when (width) {
        "Mode1" -> BarcodeWidth.Mode1
        "Mode3" -> BarcodeWidth.Mode3
        "Mode4" -> BarcodeWidth.Mode4
        "Mode5" -> BarcodeWidth.Mode5
        "Mode6" -> BarcodeWidth.Mode6
        "Mode7" -> BarcodeWidth.Mode7
        "Mode8" -> BarcodeWidth.Mode8
        "Mode9" -> BarcodeWidth.Mode9
        else -> BarcodeWidth.Mode2
    }

    private fun getInternational(international: String): InternationalType = when (international) {
        "UK" -> InternationalType.UK
        "France" -> InternationalType.France
        "Germany" -> InternationalType.Germany
        "Denmark" -> InternationalType.Denmark
        "Sweden" -> InternationalType.Sweden
        "Italy" -> InternationalType.Italy
        "Spain" -> InternationalType.Spain
        "Japan" -> InternationalType.Japan
        "Norway" -> InternationalType.Norway
        "Denmark2" -> InternationalType.Denmark2
        "Spain2" -> InternationalType.Spain2
        "LatinAmerica" -> InternationalType.LatinAmerica
        "Korea" -> InternationalType.Korea
        "Ireland" -> InternationalType.Ireland
        "Legal" -> InternationalType.Legal
        else -> InternationalType.USA
    }

    private fun createBitmapFromText(
        text: String,
        textSize: Float,
        printWidth: Int,
        typeface: Typeface
    ): Bitmap {
        val paint = Paint().apply {
            this.textSize = textSize
            this.typeface = typeface
        }
        paint.getTextBounds(text, 0, text.length, Rect())

        val textPaint = TextPaint(paint)
        val staticLayout = StaticLayout(
            text,
            textPaint,
            printWidth,
            Layout.Alignment.ALIGN_NORMAL,
            1.0f,
            0.0f,
            false
        )

        return Bitmap.createBitmap(
            staticLayout.width,
            staticLayout.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                translate(0f, 0f)
                staticLayout.draw(this)
            }
        }
    }

    private fun getCodePageType(codePageType: String): CodePageType = when (codePageType) {
        "CP437" -> CodePageType.CP437
        "CP737" -> CodePageType.CP737
        "CP772" -> CodePageType.CP772
        "CP774" -> CodePageType.CP774
        "CP851" -> CodePageType.CP851
        "CP852" -> CodePageType.CP852
        "CP855" -> CodePageType.CP855
        "CP857" -> CodePageType.CP857
        "CP858" -> CodePageType.CP858
        "CP860" -> CodePageType.CP860
        "CP861" -> CodePageType.CP861
        "CP862" -> CodePageType.CP862
        "CP863" -> CodePageType.CP863
        "CP864" -> CodePageType.CP864
        "CP865" -> CodePageType.CP865
        "CP869" -> CodePageType.CP869
        "CP874" -> CodePageType.CP874
        "CP928" -> CodePageType.CP928
        "CP932" -> CodePageType.CP932
        "CP999" -> CodePageType.CP999
        "CP1001" -> CodePageType.CP1001
        "CP1250" -> CodePageType.CP1250
        "CP1251" -> CodePageType.CP1251
        "CP1252" -> CodePageType.CP1252
        "CP2001" -> CodePageType.CP2001
        "CP3001" -> CodePageType.CP3001
        "CP3002" -> CodePageType.CP3002
        "CP3011" -> CodePageType.CP3011
        "CP3012" -> CodePageType.CP3012
        "CP3021" -> CodePageType.CP3021
        "CP3041" -> CodePageType.CP3041
        "CP3840" -> CodePageType.CP3840
        "CP3841" -> CodePageType.CP3841
        "CP3843" -> CodePageType.CP3843
        "CP3845" -> CodePageType.CP3845
        "CP3846" -> CodePageType.CP3846
        "CP3847" -> CodePageType.CP3847
        "CP3848" -> CodePageType.CP3848
        "UTF8" -> CodePageType.UTF8
        "Blank" -> CodePageType.Blank
        else -> CodePageType.CP998
    }

    private fun getEncoding(encoding: String): Charset = when (encoding) {
        "Windows-1252" -> try {
            Charset.forName("Windows-1252")  // French, German, Portuguese, Spanish
        } catch (e: UnsupportedCharsetException) {
            Charset.forName("UTF-8")
        }
        "Shift-JIS" -> try {
            Charset.forName("Shift-JIS")  // Japanese
        } catch (e: UnsupportedCharsetException) {
            Charset.forName("UTF-8")
        }
        "Windows-1251" -> try {
            Charset.forName("Windows-1251")  // Russian
        } catch (e: UnsupportedCharsetException) {
            Charset.forName("UTF-8")
        }
        "GB2312" -> try {
            Charset.forName("GB2312")  // Simplified Chinese
        } catch (e: UnsupportedCharsetException) {
            Charset.forName("UTF-8")
        }
        "Big5" -> try {
            Charset.forName("Big5")  // Traditional Chinese
        } catch (e: UnsupportedCharsetException) {
            Charset.forName("UTF-8")
        }
        "UTF-8" -> Charset.forName("UTF-8")
        else -> Charset.forName("US-ASCII")  // Default English
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
