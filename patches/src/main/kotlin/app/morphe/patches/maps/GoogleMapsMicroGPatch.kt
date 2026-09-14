package app.morphe.patches.maps

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val ORIGINAL_PACKAGE_NAME = "com.google.android.apps.maps"
private const val PATCHED_PACKAGE_NAME = "app.morphe.android.apps.maps"
private const val ORIGINAL_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788"
private const val ORIGINAL_CERT_SHA256 = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
private const val ORIGINAL_CERT_SHA256_ANDROID_13_PLUS = "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"
private const val GMS_CORE_PACKAGE_NAME = "app.revanced.android.gms"
private const val GMS_CORE_VENDOR_GROUP = "app.revanced"
private const val C2DM_PACKAGE_NAME = "app.revanced.android.c2dm"
private const val MAIN_CLASS = "Lcom/google/android/maps/MapsActivity;"
private const val MAPS_APPLICATION_CLASS = "Lcom/google/android/apps/gmm/base/app/GoogleMapsApplication;"
private const val GOOGLE_API_CLIENT_BUILDER = "Lcom/google/android/gms/common/api/GoogleApiClient\$Builder;"
private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/GmsCoreSupportPatch;"
private const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"
private const val BYD_AUDIO_CLASS =
    "Lapp/morphe/extension/maps/patches/BydNavigationAudioPatch;"
private const val LOCATION_SERVICE_CLASS =
    "Lapp/morphe/extension/maps/patches/LocationServicePatch;"
private const val LOCATION_SERVICE_ACTION =
    "com.google.android.location.internal.GoogleLocationManagerService.START"

private val compatibility = Compatibility(
    name = "Google Maps",
    packageName = ORIGINAL_PACKAGE_NAME,
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0x4285F4,
    signatures = setOf(ORIGINAL_CERT_SHA256, ORIGINAL_CERT_SHA256_ANDROID_13_PLUS),
    targets = listOf(
        AppTarget(
            version = "26.35.04.969485213",
            minSdk = 28,
        ),
        AppTarget(
            version = "26.36.05.973607363",
            minSdk = 28,
        ),
    ),
)

private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            patchManifest(document)
        }
    }
}

@Suppress("unused")
val googleMapsMicroGPatch = bytecodePatch(
    name = "Google Maps for MicroG-RE-BYD",
    description = "Connects supported Google Maps builds to MicroG-RE-BYD, with BYD navigation audio and compatibility with devices that also have official Google Play services.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(manifestPatch)
    extendWith("extensions/maps.mpe")

    execute {
        rewriteGmsCoreStrings()
        patchLocationServiceAction()
        patchExtensionRuntime()
        patchAvailabilityChecks()
        suppressMisleadingPlayServicesUpdateNotification()
        patchBydNavigationAudio()
        injectExtensionContext()
        injectGmsCoreCheck()
    }
}

private fun patchManifest(document: Document) {
    val manifest = document.documentElement
    val appOwnedPermissionRenames = collectAppOwnedPermissionRenames(document)

    manifest.setAttribute("package", PATCHED_PACKAGE_NAME)

    rewriteManifestAttributes(manifest, appOwnedPermissionRenames)
    validateAppOwnedPermissionRenames(document, appOwnedPermissionRenames)
    ensureQueryPackage(document, manifest)
    ensureSpoofMetadata(document)
}

private fun collectAppOwnedPermissionRenames(document: Document): Map<String, String> {
    val renames = linkedMapOf<String, String>()

    manifestPermissionDeclarationTags.forEach { tagName ->
        val declarations = document.getElementsByTagName(tagName)
        for (index in 0 until declarations.length) {
            val declaration = declarations.item(index) as? Element ?: continue
            val name = declaration.getAttribute("android:name")
            if (name.isBlank()) continue

            renames[name] = when {
                name.startsWith("$ORIGINAL_PACKAGE_NAME.") ->
                    name.replaceFirst(ORIGINAL_PACKAGE_NAME, PATCHED_PACKAGE_NAME)

                name.startsWith('.') -> "$PATCHED_PACKAGE_NAME$name"
                else -> "${PATCHED_PACKAGE_NAME}_$name"
            }
        }
    }

    return renames
}

private fun rewriteManifestAttributes(
    node: Node,
    appOwnedPermissionRenames: Map<String, String>,
) {
    if (node is Element) {
        val attributes = node.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = attribute.nodeName
            val value = attribute.nodeValue

            attribute.nodeValue = when {
                name == "android:authorities" -> value.replaceOriginalPackage()
                name == "android:name" && node.tagName in manifestPermissionTags ->
                    appOwnedPermissionRenames[value] ?: value.rewriteManifestRoute()

                name in manifestPermissionAttributes ->
                    appOwnedPermissionRenames[value] ?: value.rewriteManifestRoute()

                else -> value
            }
        }
    }

    val children = node.childNodes
    for (index in 0 until children.length) {
        rewriteManifestAttributes(children.item(index), appOwnedPermissionRenames)
    }
}

private fun validateAppOwnedPermissionRenames(
    document: Document,
    appOwnedPermissionRenames: Map<String, String>,
) {
    if (appOwnedPermissionRenames.isEmpty()) return

    val oldNames = appOwnedPermissionRenames.keys
    val staleNames = linkedSetOf<String>()

    fun collectStaleNames(node: Node) {
        if (node is Element) {
            val attributes = node.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                val isPermissionName =
                    attribute.nodeName == "android:name" && node.tagName in manifestPermissionTags
                val isPermissionReference = attribute.nodeName in manifestPermissionAttributes

                if ((isPermissionName || isPermissionReference) && attribute.nodeValue in oldNames) {
                    staleNames += attribute.nodeValue
                }
            }
        }

        val children = node.childNodes
        for (index in 0 until children.length) {
            collectStaleNames(children.item(index))
        }
    }

    collectStaleNames(document.documentElement)
    if (staleNames.isNotEmpty()) {
        throw PatchException("Failed to rename app-owned permissions: ${staleNames.joinToString()}")
    }

    val declaredNames = manifestPermissionDeclarationTags.flatMap { tagName ->
        val declarations = document.getElementsByTagName(tagName)
        buildList {
            for (index in 0 until declarations.length) {
                val declaration = declarations.item(index) as? Element ?: continue
                add(declaration.getAttribute("android:name"))
            }
        }
    }.toSet()
    val missingDeclarations = appOwnedPermissionRenames.values - declaredNames

    if (missingDeclarations.isNotEmpty()) {
        throw PatchException(
            "Missing renamed app-owned permission declarations: ${missingDeclarations.joinToString()}",
        )
    }
}

private fun String.replaceOriginalPackage() =
    replace(ORIGINAL_PACKAGE_NAME, PATCHED_PACKAGE_NAME)

private val manifestPermissionDeclarationTags = setOf(
    "permission",
    "permission-group",
    "permission-tree",
)

private val manifestPermissionTags = manifestPermissionDeclarationTags + setOf(
    "uses-permission",
    "uses-permission-sdk-23",
)

private val manifestPermissionAttributes = setOf(
    "android:permission",
    "android:permissionGroup",
    "android:readPermission",
    "android:writePermission",
)

private val manifestRouteReplacements = mapOf(
    "com.google.android.c2dm.permission.RECEIVE" to "$C2DM_PACKAGE_NAME.permission.RECEIVE",
    "com.google.android.c2dm.permission.SEND" to "$C2DM_PACKAGE_NAME.permission.SEND",
    "com.google.android.providers.gsf.permission.READ_GSERVICES" to "$GMS_CORE_VENDOR_GROUP.android.providers.gsf.permission.READ_GSERVICES",
    "com.google.android.gms.permission.CAR_SPEED" to "$GMS_CORE_PACKAGE_NAME.permission.CAR_SPEED",
)

private fun String.rewriteManifestRoute() =
    manifestRouteReplacements[this] ?: this

private fun ensureQueryPackage(document: Document, manifest: Element) {
    val queries = manifest.directChildren("queries").firstOrNull()
        ?: document.createElement("queries").also { queriesNode ->
            val firstChild = manifest.firstChild
            if (firstChild == null) {
                manifest.appendChild(queriesNode)
            } else {
                manifest.insertBefore(queriesNode, firstChild)
            }
        }

    val exists = queries.directChildren("package").any {
        it.getAttribute("android:name") == GMS_CORE_PACKAGE_NAME
    }

    if (!exists) {
        val packageNode = document.createElement("package")
        packageNode.setAttribute("android:name", GMS_CORE_PACKAGE_NAME)
        queries.appendChild(packageNode)
    }
}

private fun ensureSpoofMetadata(document: Document) {
    val application = document.getElementsByTagName("application").item(0) as Element

    application.setMetadata(
        document,
        "$GMS_CORE_PACKAGE_NAME.SPOOFED_PACKAGE_NAME",
        ORIGINAL_PACKAGE_NAME,
    )
    application.setMetadata(
        document,
        "$GMS_CORE_PACKAGE_NAME.SPOOFED_PACKAGE_SIGNATURE",
        ORIGINAL_CERT_SHA1,
    )
    application.setMetadata(
        document,
        "$GMS_CORE_VENDOR_GROUP.MICROG_PACKAGE_NAME",
        GMS_CORE_PACKAGE_NAME,
    )
    application.setMetadata(
        document,
        "$GMS_CORE_PACKAGE_NAME.MICROG_PACKAGE_NAME",
        GMS_CORE_PACKAGE_NAME,
    )
}

private fun Element.setMetadata(document: Document, name: String, value: String) {
    val existing = directChildren("meta-data").firstOrNull {
        it.getAttribute("android:name") == name
    }

    val metadata = existing ?: document.createElement("meta-data").also(::appendChild)
    metadata.setAttribute("android:name", name)
    metadata.setAttribute("android:value", value)
}

private fun Element.directChildren(tagName: String): List<Element> {
    val result = mutableListOf<Element>()
    val children = childNodes

    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.tagName == tagName) {
            result += child
        }
    }

    return result
}

private val exactStringReplacements = mapOf(
    "com.google" to GMS_CORE_VENDOR_GROUP,
    "subscribedfeeds" to "$GMS_CORE_VENDOR_GROUP.subscribedfeeds",
    "$ORIGINAL_PACKAGE_NAME.SuggestionProvider" to "$PATCHED_PACKAGE_NAME.SuggestionProvider",
    "$ORIGINAL_PACKAGE_NAME.fileprovider" to "$PATCHED_PACKAGE_NAME.fileprovider",
)

private val exactGmsRouteReplacements = mapOf(
    "com.google.android.c2dm.permission.RECEIVE" to "$C2DM_PACKAGE_NAME.permission.RECEIVE",
    "com.google.android.c2dm.permission.SEND" to "$C2DM_PACKAGE_NAME.permission.SEND",
    "com.google.android.gms" to GMS_CORE_PACKAGE_NAME,
    "com.google.android.gms.auth.accounts" to "$GMS_CORE_PACKAGE_NAME.auth.accounts",
    "com.google.android.gms.chimera" to "$GMS_CORE_PACKAGE_NAME.chimera",
    "com.google.android.gms.fonts" to "$GMS_CORE_PACKAGE_NAME.fonts",
    "com.google.android.gms.permission.CAR_SPEED" to "$GMS_CORE_PACKAGE_NAME.permission.CAR_SPEED",
    "com.google.android.gms.phenotype" to "$GMS_CORE_PACKAGE_NAME.phenotype",
    "com.google.android.providers.gsf.permission.READ_GSERVICES" to "$GMS_CORE_VENDOR_GROUP.android.providers.gsf.permission.READ_GSERVICES",
)

private fun transformString(value: String): String? {
    val transformed = exactStringReplacements[value]
        ?: exactGmsRouteReplacements[value]
        ?: value.toRevancedContentUriRoute()

    return transformed.takeIf { it != value }
}

private fun String.toRevancedContentUriRoute(): String = when {
    startsWith("content://com.google.android.gms.phenotype") ->
        replace("content://com.google.android.gms.phenotype", "content://$GMS_CORE_PACKAGE_NAME.phenotype")

    startsWith("content://com.google.android.gsf.gservices") ->
        replace("content://com.google.android.gsf.gservices", "content://$GMS_CORE_VENDOR_GROUP.android.gsf.gservices")

    startsWith("content://com.google.settings") ->
        replace("content://com.google.settings", "content://$GMS_CORE_VENDOR_GROUP.settings")

    startsWith("content://subscribedfeeds") ->
        replace("content://subscribedfeeds", "content://$GMS_CORE_VENDOR_GROUP.subscribedfeeds")

    else -> this
}

private fun stringReferenceOf(instruction: Any): StringReference? = when (instruction) {
    is Instruction21c -> instruction.reference as? StringReference
    is Instruction31c -> instruction.reference as? StringReference
    else -> null
}

private fun replacementFor(instruction: Any, transformed: String): BuilderInstruction? = when (instruction) {
    is Instruction21c -> BuilderInstruction21c(
        Opcode.CONST_STRING,
        instruction.registerA,
        ImmutableStringReference(transformed),
    )

    is Instruction31c -> BuilderInstruction31c(
        Opcode.CONST_STRING_JUMBO,
        instruction.registerA,
        ImmutableStringReference(transformed),
    )

    else -> null
}

private fun app.morphe.patcher.patch.BytecodePatchContext.rewriteGmsCoreStrings() {
    getAllClassesWithStrings().forEach { classDef ->
        val mutableClass = mutableClassDefBy(classDef)

        mutableClass.methods.forEach { method ->
            val implementation = method.implementation ?: return@forEach

            implementation.instructions.forEachIndexed { index, instruction ->
                val original = stringReferenceOf(instruction)?.string ?: return@forEachIndexed
                val transformed = transformString(original) ?: return@forEachIndexed
                val replacement = replacementFor(instruction, transformed) ?: return@forEachIndexed

                method.replaceInstruction(index, replacement)
            }
        }
    }
}

private fun app.morphe.patcher.patch.BytecodePatchContext.patchLocationServiceAction() {
    // Both inspected targets have two action getters with exactly const-string/return-object.
    // Match the protocol literal and method shape rather than a version-specific class name.
    val methods = getAllClassesWithStrings()
        .filterNot { it.type.startsWith("Lapp/morphe/extension/") }
        .flatMap { mutableClassDefBy(it).methods }
        .filter { method ->
            method.returnType == "Ljava/lang/String;" && method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.any {
                    stringReferenceOf(it)?.string == LOCATION_SERVICE_ACTION
                } == true
        }.toList()
    if (methods.size != 2) {
        throw PatchException("Expected two Maps location action getters, found ${methods.size}")
    }
    methods.forEach { method ->
        val instructions = method.implementation!!.instructions.toList()
        val register = when (val first = instructions.first()) {
            is Instruction21c -> first.registerA
            is Instruction31c -> first.registerA
            else -> throw PatchException("Unexpected Maps location action instruction")
        }
        if (instructions.size != 2 ||
            stringReferenceOf(instructions.first())?.string != LOCATION_SERVICE_ACTION ||
            instructions.last().opcode != Opcode.RETURN_OBJECT ||
            (instructions.last() as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                ?.registerA != register
        ) {
            throw PatchException("Unexpected Maps location action getter shape")
        }
        method.replaceInstruction(
            0, "invoke-static {}, $LOCATION_SERVICE_CLASS->getServiceAction()Ljava/lang/String;",
        )
        method.addInstruction(1, "move-result-object v$register")
    }
}

private val extensionVendorFingerprint = Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "getGmsCoreVendorGroupId",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

private val extensionOriginalPackageFingerprint = Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "getOriginalPackageName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

private val serviceCheckFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "I"),
    strings = listOf("Google Play Services not available"),
)

private val playServicesAvailabilityNotificationFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Lcom/google/android/gms/common/ConnectionResult;",
    ),
    strings = listOf("com.google.android.gms.availability"),
)

private val mediaAlertFileFingerprint = Fingerprint(
    strings = listOf(
        "MediaAlert file doesn't exist",
        "Exception creating MediaAlert from file",
    ),
)

private val mediaAlertResourceFingerprint = Fingerprint(
    strings = listOf("Error loading sound file from resource"),
)

private val mediaAlertAudioAttributesFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Landroid/media/MediaPlayer;",
        "L",
        "Ljava/util/concurrent/Executor;",
        "L",
    ),
    custom = { method, _ ->
        val references = method.implementation?.instructions
            ?.mapNotNull { it.methodReferenceOrNull() }
            ?: emptyList()
        references.any {
            it.matches(
                "Landroid/media/AudioAttributes\u0024Builder;",
                "setUsage",
                listOf("I"),
                "Landroid/media/AudioAttributes\u0024Builder;",
            )
        } && references.any {
            it.matches(
                "Landroid/media/AudioAttributes\u0024Builder;",
                "setContentType",
                listOf("I"),
                "Landroid/media/AudioAttributes\u0024Builder;",
            )
        } && references.any {
            it.matches(
                "Landroid/media/MediaPlayer;",
                "setAudioAttributes",
                listOf("Landroid/media/AudioAttributes;"),
                "V",
            )
        }
    },
)

private fun Any.methodReferenceOrNull() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.matches(
    definingClass: String,
    name: String,
    parameterTypes: List<String>,
    returnType: String,
) = this.definingClass == definingClass &&
    this.name == name &&
    this.parameterTypes.map { it.toString() } == parameterTypes &&
    this.returnType == returnType

private fun audioStreamWrapperInvoke(instruction: Any): String = when (instruction) {
    is FiveRegisterInstruction -> {
        if (instruction.registerCount != 2) {
            throw PatchException("Unexpected MediaPlayer.setAudioStreamType register count")
        }
        "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
            "$BYD_AUDIO_CLASS->setAudioStreamType(Landroid/media/MediaPlayer;I)V"
    }

    is RegisterRangeInstruction -> {
        if (instruction.registerCount != 2) {
            throw PatchException("Unexpected MediaPlayer.setAudioStreamType range count")
        }
        val endRegister = instruction.startRegister + 1
        "invoke-static/range { v${instruction.startRegister} .. v$endRegister }, " +
            "$BYD_AUDIO_CLASS->setAudioStreamType(Landroid/media/MediaPlayer;I)V"
    }

    else -> throw PatchException("Unsupported MediaPlayer.setAudioStreamType instruction format")
}

private fun audioAttributesWrapperInvoke(instruction: Any): String = when (instruction) {
    is FiveRegisterInstruction -> {
        if (instruction.registerCount != 2) {
            throw PatchException("Unexpected MediaPlayer.setAudioAttributes register count")
        }
        "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, " +
            "$BYD_AUDIO_CLASS->setAudioAttributes" +
            "(Landroid/media/MediaPlayer;Landroid/media/AudioAttributes;)V"
    }

    is RegisterRangeInstruction -> {
        if (instruction.registerCount != 2) {
            throw PatchException("Unexpected MediaPlayer.setAudioAttributes range count")
        }
        val endRegister = instruction.startRegister + 1
        "invoke-static/range { v${instruction.startRegister} .. v$endRegister }, " +
            "$BYD_AUDIO_CLASS->setAudioAttributes" +
            "(Landroid/media/MediaPlayer;Landroid/media/AudioAttributes;)V"
    }

    else -> throw PatchException("Unsupported MediaPlayer.setAudioAttributes instruction format")
}

private fun app.morphe.patcher.patch.BytecodePatchContext.patchBydNavigationAudio() {
    listOf(
        "file MediaAlert" to mediaAlertFileFingerprint,
        "resource MediaAlert" to mediaAlertResourceFingerprint,
    ).forEach { (label, fingerprint) ->
        val method = fingerprint.methodOrNull
            ?: throw PatchException("Failed to match $label audio stream hook")
        val matches = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
            instruction.methodReferenceOrNull()?.matches(
                "Landroid/media/MediaPlayer;",
                "setAudioStreamType",
                listOf("I"),
                "V",
            ) == true
        }

        if (matches.size != 1) {
            throw PatchException("Expected exactly one $label audio stream call, found ${matches.size}")
        }

        val (index, instruction) = matches.single()
        method.replaceInstruction(index, audioStreamWrapperInvoke(instruction))
    }

    val attributesMethod = mediaAlertAudioAttributesFingerprint.methodOrNull
        ?: throw PatchException("Failed to match MediaAlert AudioAttributes hook")
    val attributeMatches = attributesMethod.implementation!!.instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.methodReferenceOrNull()?.matches(
                "Landroid/media/MediaPlayer;",
                "setAudioAttributes",
                listOf("Landroid/media/AudioAttributes;"),
                "V",
            ) == true
        }
    if (attributeMatches.size != 1) {
        throw PatchException(
            "Expected exactly one MediaAlert AudioAttributes call, found ${attributeMatches.size}",
        )
    }

    val (attributeIndex, attributeInstruction) = attributeMatches.single()
    attributesMethod.replaceInstruction(
        attributeIndex,
        audioAttributesWrapperInvoke(attributeInstruction),
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.patchExtensionRuntime() {
    val vendorMethod = extensionVendorFingerprint.methodOrNull
        ?: throw PatchException("Failed to match GmsCore extension vendor hook")
    vendorMethod.addInstructions(
        0,
        """
            const-string v0, "$GMS_CORE_VENDOR_GROUP"
            return-object v0
        """.trimIndent(),
    )

    val originalPackageMethod = extensionOriginalPackageFingerprint.methodOrNull
        ?: throw PatchException("Failed to match GmsCore extension original package hook")
    originalPackageMethod.addInstructions(
        0,
        """
            const-string v0, "$ORIGINAL_PACKAGE_NAME"
            return-object v0
        """.trimIndent(),
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.patchAvailabilityChecks() {
    serviceCheckFingerprint.methodOrNull?.addInstruction(0, "return-void")

    val googleApiClientBuilderClass = classDefByOrNull(GOOGLE_API_CLIENT_BUILDER)
        ?: throw PatchException("Failed to find Google API Client class")
    val testingMethod = googleApiClientBuilderClass.methods.find { method ->
        method.returnType == GOOGLE_API_CLIENT_BUILDER &&
                method.name == "setApiAvailabilityForTesting"
    } ?: throw PatchException("Failed to find setApiAvailabilityForTesting method")
    val childClassName = testingMethod.parameterTypes.firstOrNull()
        ?: throw PatchException("Failed to find parent of Google Play services availability")
    val definingClass = classDefByOrNull(childClassName.toString())?.superclass
        ?: throw PatchException("Failed to find Google Play services class")
    val googlePlayUtilityFingerprint = Fingerprint(
        definingClass = definingClass,
        returnType = "I",
        parameters = listOf("Landroid/content/Context;", "I"),
    )
    val method = googlePlayUtilityFingerprint.methodOrNull
        ?: throw PatchException("Failed to match Google Play services availability")

    method.addInstructions(
        0,
        """
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.suppressMisleadingPlayServicesUpdateNotification() {
    val method = playServicesAvailabilityNotificationFingerprint.methodOrNull
        ?: throw PatchException("Failed to match Google Play services availability notification")

    method.addInstructions(
        0,
        """
            iget v0, p2, Lcom/google/android/gms/common/ConnectionResult;->c:I
            const/4 v1, 0x2
            if-ne v0, v1, :show_notification
            return-void
            :show_notification
            nop
        """.trimIndent(),
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.injectExtensionContext() {
    val definingClass = classDefByOrNull(MAPS_APPLICATION_CLASS)?.superclass
        ?: throw PatchException("Failed to find Maps application superclass")
    val mapsApplicationOnCreateFingerprint = Fingerprint(
        definingClass = definingClass,
        name = "onCreate",
        returnType = "V",
        parameters = listOf(),
    )
    val method = mapsApplicationOnCreateFingerprint.methodOrNull
        ?: throw PatchException("Failed to match Maps application onCreate")

    method.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $UTILS_CLASS->setContext(Landroid/content/Context;)V",
    )
}

private fun app.morphe.patcher.patch.BytecodePatchContext.injectGmsCoreCheck() {
    val definingClass = classDefByOrNull(MAIN_CLASS)?.superclass
        ?: throw PatchException("Failed to find Maps activity superclass")
    val mapsActivityOnCreateFingerprint = Fingerprint(
        definingClass = definingClass,
        name = "onCreate",
        returnType = "V",
        parameters = listOf("Landroid/os/Bundle;"),
    )
    val method = mapsActivityOnCreateFingerprint.methodOrNull
        ?: throw PatchException("Failed to match Maps activity onCreate")

    method.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->checkGmsCore(Landroid/app/Activity;)V",
    )
}
