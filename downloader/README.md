# TurboDL — Download Accelerator

TurboDL downloads big files (movies, ISOs, installers) **faster** by opening several
connections to the server at once, each fetching a different part of the file.
If the download stops — you pause it, close the app, the Wi‑Fi drops, the PC
restarts — it **continues from where it stopped** instead of starting over.

![TurboDL window](screenshot.png)

## Features

- **Up to 32 connections per file** (8 by default), with *work stealing*: when a
  connection finishes its part early, it takes over half of the slowest part still
  left, so all connections stay busy until the end.
- **Pause / resume**, even after closing the app or rebooting. Unfinished
  downloads reappear in the list next time.
- **Automatic retries** with back-off on dropped connections and server errors.
- **Speed limit** (e.g. `2M`) so downloads don't slow down everything else.
- **SHA‑256 check** (command line) to confirm the file is not damaged.
- Follows redirects, picks the right file name, never overwrites an existing file
  (`movie (1).mp4`), and falls back to a single connection on servers that don't
  support split downloads.
- Desktop window **and** command line. Only the Python standard library — nothing
  else to install.

## How much faster?

It depends on where the bottleneck is:

| Situation | Effect |
|---|---|
| The server or CDN limits the speed **per connection** (very common for file hosts and big downloads) | Large speed-up — often 3–8× |
| Long distance / high latency to the server | Noticeable speed-up |
| Your internet plan is already fully used by one connection | No speed-up — nothing can exceed your line speed |

In a test against a real server, a 55 MB file took 9.7 s with one connection and
5.5 s with eight.

## Get the app

**Windows / macOS / Linux, ready to run:** open the repository's **Actions** tab →
*TurboDL (test and build apps)* → the latest run → **Artifacts**, and download
`TurboDL-Windows` (a single `TurboDL.exe`), `TurboDL-macOS` or `TurboDL-Linux`.

**From source** (Python 3.10+):

```bash
cd downloader
python TurboDL.pyw            # open the window (or double-click TurboDL.pyw)
python -m turbodl URL         # command line
pip install .                 # optional: adds `turbodl` and `turbodl-gui` commands
```

On Linux the window needs Tk: `sudo apt install python3-tk`.

## Using the window

1. Copy a download link, click **Paste**, then **Download** (or press Enter).
2. Pick the folder under **Save to** and the number of **Connections** (8 is a good
   start; try 16 for slow servers).
3. Select a row to **Pause**, **Resume**, **Cancel** (deletes the partial file) or
   **Open** the finished file. Double-click a finished row to open it.

Settings and unfinished downloads are kept in `~/.turbodl/settings.json`.

## Command line

```bash
turbodl https://example.com/movie.mkv                     # save in current folder
turbodl -c 16 -d ~/Videos https://example.com/movie.mkv   # 16 connections, chosen folder
turbodl -l 2M URL                                         # limit to 2 MB/s
turbodl --sha256 <checksum> URL                           # verify after download
turbodl -H "Cookie: session=abc" URL                      # links that need a login cookie
turbodl URL1 URL2 URL3                                    # several files, one after another
```

Press **Ctrl+C** to pause; run the same command again to continue.

## How it works

1. A tiny request (`Range: bytes=0-0`) finds the size, file name, and whether the
   server allows partial downloads.
2. A `<name>.part` file of the full size is created and split into pieces, one per
   connection; each connection downloads its piece and writes it into place.
3. Progress is saved every 2 seconds to `<name>.part.turbodl`. On resume, the
   saved pieces are checked against the server's `ETag`/`Last-Modified`, so a file
   that changed on the server is never mixed with the old one.
4. When everything has arrived (and the checksum matches, if given), `.part` is
   renamed to the final name.

## Notes

- Only download content you have the right to download.
- Links from streaming sites (Netflix, YouTube, etc.) are not direct file links and
  won't work; TurboDL is for direct HTTP/HTTPS download links.

## Tests

```bash
cd downloader && pip install pytest && python -m pytest -q
```

The tests run a local server that supports partial downloads and limits each
connection's speed, and check parallel download, speed-up, work stealing, retries,
pause/resume across restarts, cancel, redirects, file naming and checksums.
