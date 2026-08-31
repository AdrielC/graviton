# Apache Tika Is Not Shipped

Graviton does not contain a `graviton-tika` project, Tika dependency, parser pool, configuration namespace, route, or metric. No current upload invokes Apache Tika.

The runtime performs a bounded byte-signature probe for upload routing. PDF uploads can then use the separate [`graviton-pdf`](./pdf.md) adapter for signature validation and structural block boundaries. That path does not extract text or general document metadata.

A future broad-format integration would have to remain optional and outside the core byte path. It would need explicit byte and time bounds, a typed failure contract, interruption-safe parser resources, representative malicious and malformed fixtures, and a decision about where extracted metadata belongs. Until that implementation and proof exist, users should run Tika or another parser in a downstream service after retrieving a Graviton content ID.

This page is retained only to make the absence explicit. It is not listed as a Graviton module or supported format surface.
