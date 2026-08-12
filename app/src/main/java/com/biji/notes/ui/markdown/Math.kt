package com.biji.notes.ui.markdown

/**
 * Best-effort LaTeX → Unicode substitution for inline math and small
 * `$$…$$` blocks. We don't try to be a real KaTeX — that requires a
 * WebView. Instead we lower the most common patterns (greek letters,
 * super/sub-scripts, common operators, simple fractions) into Unicode
 * so the rendered text reads like math at a glance.
 */
internal fun latexToUnicode(raw: String): String {
    // C++ 版是表驱动单遍扫描，对齐下面这套正则实现的语义。
    if (com.biji.notes.nativebridge.NativeGate.text) {
        com.biji.notes.nativebridge.NativeText.latexToUnicode(raw)?.let { return it }
    }
    return runCatching { latexToUnicodeImpl(raw) }.getOrElse { raw }
}

private fun latexToUnicodeImpl(raw: String): String {
    var s = raw.trim()
    // Strip the outer `$` if the inline path didn't already.
    s = s.removePrefix("$$").removeSuffix("$$")
    s = s.removePrefix("$").removeSuffix("$")
    // Greek + named operators.
    for ((k, v) in GreekMap) s = s.replace(k, v)
    // \frac{a}{b}  →  a / b
    // Raw-string patterns so the ICU regex compiler doesn't choke on
    // standalone `}` (it requires `\}` outside `{n,m}` quantifiers).
    s = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""").replace(s) { m ->
        "${m.groupValues[1]} / ${m.groupValues[2]}"
    }
    // \sqrt{x}  →  √x
    s = Regex("""\\sqrt\{([^{}]+)\}""").replace(s) { m -> "√${m.groupValues[1]}" }
    // \sum / \int  →  ∑ / ∫
    s = s.replace("\\sum", "∑").replace("\\int", "∫")
    s = s.replace("\\infty", "∞").replace("\\partial", "∂")
    s = s.replace("\\cdot", "·").replace("\\times", "×").replace("\\div", "÷")
    s = s.replace("\\le", "≤").replace("\\ge", "≥").replace("\\ne", "≠")
    s = s.replace("\\approx", "≈").replace("\\equiv", "≡")
    s = s.replace("\\rightarrow", "→").replace("\\leftarrow", "←")
    s = s.replace("\\Rightarrow", "⇒").replace("\\Leftarrow", "⇐")
    // Super/subscripts: ^{abc} or ^a, _{abc} or _a — translate to Unicode
    // where possible, otherwise inline the original characters.
    s = Regex("""\^\{([^{}]+)\}""").replace(s) { m -> superscript(m.groupValues[1]) }
    s = Regex("""\^([0-9A-Za-z+\-=()])""").replace(s) { m -> superscript(m.groupValues[1]) }
    s = Regex("""_\{([^{}]+)\}""").replace(s) { m -> subscript(m.groupValues[1]) }
    s = Regex("""_([0-9A-Za-z+\-=()])""").replace(s) { m -> subscript(m.groupValues[1]) }
    // Stray backslash-commands we didn't recognise: strip the slash.
    s = Regex("\\\\([A-Za-z]+)").replace(s) { m -> m.groupValues[1] }
    // Common cleanup.
    s = s.replace("\\{", "{").replace("\\}", "}").replace("\\\\", "\n")
    return s
}

private val GreekMap = mapOf(
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
    "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
    "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
    "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\sigma" to "σ",
    "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "φ",
    "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
    "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
    "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
)

private val Superscripts = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
    'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
    'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
    'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
    'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
)

private val Subscripts = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ'
)

private fun superscript(s: String): String =
    s.map { Superscripts[it]?.toString() ?: "^$it" }.joinToString("")

private fun subscript(s: String): String =
    s.map { Subscripts[it]?.toString() ?: "_$it" }.joinToString("")
