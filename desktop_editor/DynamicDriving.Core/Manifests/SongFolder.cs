using System.Text;
using System.Text.RegularExpressions;

namespace DynamicDriving.Core.Manifests;

/// <summary>A song folder on disk: its manifest, the WAVs beside it, and its cover art.</summary>
public sealed class SongFolder
{
    public const string ManifestFileName = "song.json";

    private static readonly string[] CoverFileNames =
    {
        "cover.png", "cover.jpg", "cover.jpeg", "cover.webp"
    };

    private SongFolder(string folderPath, SongManifest manifest, IReadOnlyList<string> wavFiles, bool manifestExisted)
    {
        FolderPath = folderPath;
        Manifest = manifest;
        WavFiles = wavFiles;
        ManifestExisted = manifestExisted;
    }

    public string FolderPath { get; }

    public SongManifest Manifest { get; }

    /// <summary>Every `.wav` directly inside the folder, in stable alphabetical order.</summary>
    public IReadOnlyList<string> WavFiles { get; }

    /// <summary>False when the folder had no song.json and a starting manifest was invented.</summary>
    public bool ManifestExisted { get; }

    public string ManifestPath => Path.Combine(FolderPath, ManifestFileName);

    public string? CoverPath
    {
        get
        {
            foreach (var name in CoverFileNames)
            {
                var candidate = Path.Combine(FolderPath, name);
                if (File.Exists(candidate))
                {
                    return candidate;
                }
            }

            return null;
        }
    }

    /// <summary>
    /// Opens a folder.
    ///
    /// A folder without a song.json is not an error: the editor builds a starting manifest from the
    /// WAVs it finds so a new song can be authored from nothing.
    /// </summary>
    public static SongFolder Open(string folderPath)
    {
        if (!Directory.Exists(folderPath))
        {
            throw new DirectoryNotFoundException($"\"{folderPath}\" does not exist.");
        }

        var wavFiles = ScanWavFiles(folderPath);
        var manifestPath = Path.Combine(folderPath, ManifestFileName);

        if (File.Exists(manifestPath))
        {
            var manifest = SongManifestSerializer.Deserialize(File.ReadAllText(manifestPath));
            return new SongFolder(folderPath, manifest, wavFiles, manifestExisted: true);
        }

        return new SongFolder(folderPath, CreateDefaultManifest(folderPath, wavFiles), wavFiles, manifestExisted: false);
    }

    public static IReadOnlyList<string> ScanWavFiles(string folderPath)
    {
        if (!Directory.Exists(folderPath))
        {
            return Array.Empty<string>();
        }

        return Directory
            .EnumerateFiles(folderPath, "*.wav", SearchOption.TopDirectoryOnly)
            .Select(Path.GetFileName)
            .Where(name => !string.IsNullOrEmpty(name))
            .Select(name => name!)
            .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public static SongManifest CreateDefaultManifest(string folderPath, IReadOnlyList<string> wavFiles)
    {
        var folderName = new DirectoryInfo(folderPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)).Name;
        var manifest = new SongManifest
        {
            SongId = SanitizeIdentifier(folderName),
            DisplayName = ToDisplayLabel(folderName),
            LoopRegion = new LoopRegion { StartMs = 0, EndMs = 10_000 }
        };

        foreach (var wav in wavFiles)
        {
            manifest.Stems.Add(CreateDefaultStem(wav));
        }

        manifest.TransportStemId = manifest.Stems.FirstOrDefault()?.StemId ?? string.Empty;
        return manifest;
    }

    public static StemManifest CreateDefaultStem(string assetPath) => new()
    {
        StemId = SanitizeIdentifier(Path.GetFileNameWithoutExtension(assetPath)),
        DisplayName = ToDisplayLabel(Path.GetFileNameWithoutExtension(assetPath)),
        AssetPath = assetPath.Replace('\\', '/'),
        Rule = new BaseStemRule()
    };

    /// <summary>
    /// Writes song.json to [folderPath] atomically.
    ///
    /// The manifest is serialized and validated in full before anything on disk is touched, then
    /// written to a temporary file in the same folder and swapped in. A failure at any point leaves
    /// the previous song.json exactly as it was.
    /// </summary>
    public static void Save(SongManifest manifest, string folderPath)
    {
        var report = SongManifestValidator.Validate(manifest, folderPath);
        if (!report.IsValid)
        {
            throw new InvalidOperationException(
                string.Join(Environment.NewLine, report.Errors.Select(issue => issue.ToString())));
        }

        var json = SongManifestSerializer.Serialize(manifest);

        // Re-reading what we are about to write guarantees a manifest we cannot load ourselves
        // never replaces one that works.
        _ = SongManifestSerializer.Deserialize(json);

        Directory.CreateDirectory(folderPath);
        var targetPath = Path.Combine(folderPath, ManifestFileName);
        var temporaryPath = Path.Combine(folderPath, $"{ManifestFileName}.{Guid.NewGuid():N}.tmp");

        try
        {
            File.WriteAllText(temporaryPath, json, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            if (File.Exists(targetPath))
            {
                File.Replace(temporaryPath, targetPath, destinationBackupFileName: null, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(temporaryPath, targetPath);
            }
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                try
                {
                    File.Delete(temporaryPath);
                }
                catch (IOException)
                {
                    // A leftover temp file is untidy but harmless; never mask the original failure.
                }
            }
        }
    }

    /// <summary>Copies [sourceImagePath] into the song folder as its cover art.</summary>
    public static string ImportCover(string folderPath, string sourceImagePath)
    {
        var extension = Path.GetExtension(sourceImagePath).ToLowerInvariant();
        if (extension is not (".png" or ".jpg" or ".jpeg" or ".webp"))
        {
            throw new NotSupportedException("Cover art must be a PNG, JPEG, or WebP image.");
        }

        var targetPath = Path.Combine(folderPath, $"cover{extension}");
        var sourceFullPath = Path.GetFullPath(sourceImagePath);
        var targetFullPath = Path.GetFullPath(targetPath);
        if (string.Equals(sourceFullPath, targetFullPath, StringComparison.OrdinalIgnoreCase))
        {
            RemoveAlternateCovers(folderPath, targetFullPath);
            return targetFullPath;
        }

        var temporaryPath = Path.Combine(folderPath, $".cover-{Guid.NewGuid():N}{extension}.tmp");
        try
        {
            File.Copy(sourceFullPath, temporaryPath, overwrite: false);
            if (File.Exists(targetFullPath))
            {
                File.Replace(temporaryPath, targetFullPath, destinationBackupFileName: null, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(temporaryPath, targetFullPath);
            }

            RemoveAlternateCovers(folderPath, targetFullPath);
            return targetFullPath;
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                try
                {
                    File.Delete(temporaryPath);
                }
                catch (IOException)
                {
                    // Never hide the import failure behind best-effort temp cleanup.
                }
            }
        }
    }

    private static void RemoveAlternateCovers(string folderPath, string retainedCoverPath)
    {
        foreach (var name in CoverFileNames)
        {
            var existing = Path.GetFullPath(Path.Combine(folderPath, name));
            if (!string.Equals(existing, retainedCoverPath, StringComparison.OrdinalIgnoreCase) && File.Exists(existing))
            {
                File.Delete(existing);
            }
        }
    }

    public static string SanitizeIdentifier(string name)
    {
        var sanitized = Regex.Replace(name.ToLowerInvariant(), "[^a-z0-9]+", "_").Trim('_');
        return string.IsNullOrEmpty(sanitized) ? "stem" : sanitized;
    }

    public static string ToDisplayLabel(string name)
    {
        var words = Regex.Split(name, "[_\\-\\s]+")
            .Where(word => !string.IsNullOrWhiteSpace(word))
            .Select(word => char.ToUpperInvariant(word[0]) + word[1..].ToLowerInvariant());
        var label = string.Join(' ', words);
        return string.IsNullOrWhiteSpace(label) ? "Stem" : label;
    }
}
