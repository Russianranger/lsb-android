# PlayOnline version metadata repair (0.5.40)

The .39 phone report reaches POL-1168, “Unable to verify version information”.
Its current session is `9f51c760-ea5c-4ea6-9ca4-1f325898fcae`; all six COM
operations and all eleven dependency imports pass. The viewer remains running
until Stop. Preparation takes 72.067 seconds and the first viewer window appears
17.842 seconds after launch. No rendering or translator change follows from
these numbers.

## Diagnosis

The .39 DNS bindings work for the public website. Its other diagnostic target,
qc000.pol.com, returns NXDOMAIN through public DNS as well as failing on the
phone. That target was selected from an old embedded string without establishing
that it was still active. It must not be treated as proof of phone DNS failure.
The replacement content-info name ci000.pol.com resolves; the check still does
not assert a successful patch-service connection or download.

In the official public app.dll, the version-read branch passes a buffer of at
most 288 bytes to the polcore common-function table entry at offset 0x124c.
At app RVA 0x296367 the decoder is called; a negative result reaches RVA
0x296385, which stores exactly -1168. A missing file takes a different branch.
This is concrete evidence of a local version-record decoding failure path.
It does not establish the contents of the phone's viewer patch.ver, which is
not included in its support archive.

The official MSI Registry table writes a separate 32-bit
`Software\PlayOnlineUS\Interface` value `1000`, a string `001b1394`.
Our initial setup wrote InstallFolder paths and language but omitted this
viewer Interface entry. The accepted game repair covers Interface `0001` only.
The decoder formats content IDs as decimal `%04d`; the viewer ID is decimal
1000, not hexadecimal 0x1000.

The earlier supplied polcore.dll exactly matches the phone's core hash:
`73b1864bf522d3aa6ec28e9f4bb226fcee956f7f4848a238c9c2d756dc703b06`.
Its original encoder (RVA 0x81a0) generated two synthetic viewer version records
for content ID 1000 and the known zero/official-installer registry candidates.
The original decoder callback (RVA 0x4a0e0), with only its registry lookup
substituted, returns 20260925_1 with each matching candidate and fails with a
missing or wrong candidate. All input bytes remain unchanged. The previously
supplied game record still returns 30251204_1 with its accepted zero value.

## Repair boundaries

The updater checks only the staged viewer's own patch.ver and selected region's
32-bit Interface `1000`. Existing values of any type are preserved. A missing
value is eligible only when one known candidate uniquely validates the complete
288-byte record, checksum, canonical padding and bounded version string. Unknown,
missing or unreadable files are not changed. No version file is synthesized or
rewritten and no arbitrary installation key is guessed.

A successful write is read back exactly; failed readback rolls back only the
new value. Registry failures retain numeric error and rollback results. The
receipt contains fixed state/candidate labels, content ID, validated version and
numeric errors, with no original registry values or paths. The operation shares
the existing installation-path worker, adding no extra Wine process startup.
The accepted game Interface `0001` behavior stays unchanged.

The fresh official-installer online diagnostic is an account-free comparison
with and without its exact MSI Interface value. Screenshots and fixed network
metadata are evidence for review, not automatic proof of completed updating.
Raw traces and proprietary installer files are excluded from artifacts.

The supplied phone archive does not contain the viewer version record or its
registry state. The guarded repair therefore addresses a verified failure mode;
final acceptance requires retrying the retained staged copy on the phone and
examining viewer_version_config if the error remains.
