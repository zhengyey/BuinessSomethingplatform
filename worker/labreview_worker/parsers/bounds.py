def fit_text(text, limit):
    """Apply limits measured in UTF-16 code units by the Java callback validator."""
    encoded = text.encode("utf-16-le")
    if len(encoded) <= limit * 2:
        return text
    return encoded[:limit * 2].decode("utf-16-le", errors="ignore")
