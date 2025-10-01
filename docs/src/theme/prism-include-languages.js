module.exports = function prismIncludeLanguages(PrismObject) {
  // Scala language definition
  PrismObject.languages.scala = {
    'triple-quoted-string': {
      pattern: /"""[\s\S]*?"""/,
      alias: 'string'
    },
    'string': {
      pattern: /("|')(?:\\.|(?!\1)[^\\\r\n])*\1/,
      greedy: true
    },
    'keyword': /\b(?:abstract|case|catch|class|def|do|else|extends|final|finally|for|forSome|if|implicit|import|lazy|match|new|null|object|override|package|private|protected|return|sealed|self|super|this|throw|trait|try|type|val|var|while|with|yield)\b/,
    'builtin': /\b(?:String|Int|Long|Short|Byte|Boolean|Double|Float|Char|Any|AnyRef|AnyVal|Unit|Nothing)\b/,
    'number': /\b0x(?:[\da-f]*\.)?[\da-f]+|(?:\d+\.?\d*|\.\d+)(?:e\d+)?[dfl]?/i,
    'symbol': /'[^\d\s\\]\w*/,
    'function': /\b(?!(?:if|while|for|return|else)\b)[a-z_]\w*(?=\s*[({])/i,
    'punctuation': /[{}[\];(),.:]/,
    'operator': /<-|=>|\b(?:->|<-|<:|>:|\|)\b|[+\-*/%&|^!=<>]=?|\b(?:and|or)\b/
  };
};
