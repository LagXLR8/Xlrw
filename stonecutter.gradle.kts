plugins {
    id("dev.kikugie.stonecutter")
}

// Version whose code is "uncommented" in src/ right now (switch with the "Set active project to ..." tasks)
stonecutter active "1.21.1"

stonecutter parameters {
    replacements {
        // 1.21.11 renamed ResourceLocation to Identifier
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
    }
}
