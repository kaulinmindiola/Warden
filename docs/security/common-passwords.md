# Common-password list

Resolves SRS TBD-01. Used by `PasswordPolicy` (REQ-FUNC-003, DEC-008).

| Item | Value |
|------|-------|
| File | `src/main/resources/security/common-passwords.txt` |
| Source | SecLists, `Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt` |
| Source commit | `c5a0b8c794699aa351e1425be4a33b2b88a20d1b` |
| Source SHA-256 | `c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0` (99,840 entries) |
| Licence | MIT, Copyright (c) 2018 Daniel Miessler |
| Bundled entries | 1,195 |
| Bundled SHA-256 | `ed0469a2ad1fd4afa94b53d0878eaaa80018a843d6de3fbfaa94a7c68740c976` |

## Derivation

The source is the list of the 100,000 passwords most frequently found in breaches, published by
the UK National Cyber Security Centre and redistributed by SecLists. The bundled file keeps only
the entries that the length rule alone would accept, because shorter or longer entries are
already rejected before the list is consulted:

1. Keep entries of 12 to 128 characters.
2. Drop entries in the `$HEX[…]` notation of the source.
3. Lower-case with the invariant culture, remove duplicates and sort by ordinal value.
4. Write one entry per line, UTF-8 without BOM, LF line endings.

The comparison ignores case: a candidate password is lower-cased with `Locale.ROOT` before the
lookup.

## Licence notice

SecLists is distributed under the MIT License:

> Copyright (c) 2018 Daniel Miessler
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software
> and associated documentation files (the "Software"), to deal in the Software without
> restriction, including without limitation the rights to use, copy, modify, merge, publish,
> distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
> Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
> BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.