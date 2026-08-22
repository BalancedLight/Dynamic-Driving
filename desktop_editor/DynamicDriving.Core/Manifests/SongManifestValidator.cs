using DynamicDriving.Core.Audio;

namespace DynamicDriving.Core.Manifests;

public enum ValidationSeverity
{
    Warning,
    Error
}

public sealed record ValidationIssue(ValidationSeverity Severity, string Message, string? StemId = null)
{
    public override string ToString() => StemId is null ? Message : $"{StemId}: {Message}";
}

public sealed record ValidationReport(IReadOnlyList<ValidationIssue> Issues)
{
    public static ValidationReport Empty { get; } = new(Array.Empty<ValidationIssue>());

    public IEnumerable<ValidationIssue> Errors =>
        Issues.Where(issue => issue.Severity == ValidationSeverity.Error);

    public IEnumerable<ValidationIssue> Warnings =>
        Issues.Where(issue => issue.Severity == ValidationSeverity.Warning);

    public bool IsValid => !Errors.Any();
}

/// <summary>
/// Checks the rules the Android engine enforces at load time, so a manifest that saves cleanly here
/// also plays in the car.
/// </summary>
public static class SongManifestValidator
{
    /// <summary>
    /// Validates [manifest]. When [folderPath] is supplied, the stems' WAV headers are read too, so
    /// mismatched sample rates and out-of-range loop points are caught before saving.
    /// </summary>
    public static ValidationReport Validate(SongManifest manifest, string? folderPath = null)
    {
        var issues = new List<ValidationIssue>();

        if (string.IsNullOrWhiteSpace(manifest.SongId))
        {
            issues.Add(new ValidationIssue(ValidationSeverity.Error, "Song ID cannot be empty."));
        }

        if (string.IsNullOrWhiteSpace(manifest.DisplayName))
        {
            issues.Add(new ValidationIssue(ValidationSeverity.Error, "Display name cannot be empty."));
        }

        if (manifest.Stems.Count == 0)
        {
            issues.Add(new ValidationIssue(ValidationSeverity.Error, "A song needs at least one stem."));
            return new ValidationReport(issues);
        }

        var duplicateStemIds = manifest.Stems
            .GroupBy(stem => stem.StemId, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key)
            .ToList();
        foreach (var duplicate in duplicateStemIds)
        {
            issues.Add(new ValidationIssue(
                ValidationSeverity.Error,
                $"Stem ID \"{duplicate}\" is used more than once."));
        }

        foreach (var stem in manifest.Stems)
        {
            if (string.IsNullOrWhiteSpace(stem.StemId))
            {
                issues.Add(new ValidationIssue(ValidationSeverity.Error, "Every stem needs an ID.", stem.AssetPath));
            }

            if (string.IsNullOrWhiteSpace(stem.AssetPath))
            {
                issues.Add(new ValidationIssue(ValidationSeverity.Error, "Every stem needs an asset path.", stem.StemId));
            }
            else if (!IsSafeRelativeAssetPath(stem.AssetPath))
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    "The asset path must be a relative path inside the song folder, without empty or dot segments.",
                    stem.StemId));
            }

            if (stem.Rule is OverlayStemRule overlay)
            {
                if (string.IsNullOrWhiteSpace(overlay.GroupId))
                {
                    issues.Add(new ValidationIssue(
                        ValidationSeverity.Error,
                        "An overlay stem needs an overlay group ID.",
                        stem.StemId));
                }

                if (overlay.CooldownMaxMs < overlay.CooldownMinMs)
                {
                    issues.Add(new ValidationIssue(
                        ValidationSeverity.Warning,
                        "Maximum cooldown is below minimum cooldown; the minimum will be used.",
                        stem.StemId));
                }
            }

            foreach (var stemEvent in stem.Events)
            {
                if (stemEvent.Modifiers.Count == 0)
                {
                    issues.Add(new ValidationIssue(
                        ValidationSeverity.Error,
                        $"Event \"{stemEvent.EventId}\" has no modifiers.",
                        stem.StemId));
                }
            }
        }

        if (manifest.Stems.All(stem => stem.StemId != manifest.TransportStemId))
        {
            issues.Add(new ValidationIssue(
                ValidationSeverity.Error,
                $"Transport stem \"{manifest.TransportStemId}\" is not one of the stems."));
        }

        if (manifest.LoopRegion.StartMs < 0)
        {
            issues.Add(new ValidationIssue(
                ValidationSeverity.Error,
                "Loop start must be non-negative."));
        }

        if (manifest.LoopRegion.EndMs <= manifest.LoopRegion.StartMs)
        {
            issues.Add(new ValidationIssue(
                ValidationSeverity.Error,
                "Loop end must be after loop start."));
        }

        if (folderPath is not null)
        {
            ValidateAudio(manifest, folderPath, issues);
        }

        return new ValidationReport(issues);
    }

    private static void ValidateAudio(SongManifest manifest, string folderPath, List<ValidationIssue> issues)
    {
        WavHeader? reference = null;
        string? referenceStemId = null;

        foreach (var stem in manifest.Stems)
        {
            if (!TryResolveAssetPath(folderPath, stem.AssetPath, out var stemPath))
            {
                if (!string.IsNullOrWhiteSpace(stem.AssetPath) && IsSafeRelativeAssetPath(stem.AssetPath))
                {
                    issues.Add(new ValidationIssue(
                        ValidationSeverity.Error,
                        $"\"{stem.AssetPath}\" could not be resolved inside the song folder.",
                        stem.StemId));
                }
                continue;
            }

            if (!File.Exists(stemPath))
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    $"\"{stem.AssetPath}\" was not found in the song folder.",
                    stem.StemId));
                continue;
            }

            WavHeader header;
            try
            {
                header = WavFile.ReadHeader(stemPath);
            }
            catch (Exception error) when (error is WavFormatException or IOException)
            {
                issues.Add(new ValidationIssue(ValidationSeverity.Error, error.Message, stem.StemId));
                continue;
            }

            if (reference is null)
            {
                reference = header;
                referenceStemId = stem.StemId;
                continue;
            }

            if (header.SampleRate != reference.SampleRate)
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    $"Sample rate {header.SampleRate} Hz does not match \"{referenceStemId}\" ({reference.SampleRate} Hz).",
                    stem.StemId));
            }

            if (header.ChannelCount != reference.ChannelCount)
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    $"Channel count {header.ChannelCount} does not match \"{referenceStemId}\" ({reference.ChannelCount}).",
                    stem.StemId));
            }

            if (header.BitsPerSample != reference.BitsPerSample)
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    $"Bit depth {header.BitsPerSample} does not match \"{referenceStemId}\" ({reference.BitsPerSample}).",
                    stem.StemId));
            }

            if (header.FrameCount != reference.FrameCount)
            {
                issues.Add(new ValidationIssue(
                    ValidationSeverity.Error,
                    $"Length {header.DurationMs:F0} ms does not match \"{referenceStemId}\" ({reference.DurationMs:F0} ms).",
                    stem.StemId));
            }
        }

        if (reference is not null && manifest.LoopRegion.EndMs > Math.Ceiling(reference.DurationMs))
        {
            issues.Add(new ValidationIssue(
                ValidationSeverity.Error,
                $"Loop end ({manifest.LoopRegion.EndMs} ms) is past the end of the audio ({reference.DurationMs:F0} ms)."));
        }
    }

    private static bool IsSafeRelativeAssetPath(string assetPath)
    {
        if (Path.IsPathRooted(assetPath))
        {
            return false;
        }

        var normalized = assetPath.Replace('\\', '/');
        var segments = normalized.Split('/', StringSplitOptions.None);
        return segments.Length > 0 &&
            segments.All(segment => !string.IsNullOrWhiteSpace(segment) && segment is not "." and not "..");
    }

    private static bool TryResolveAssetPath(string folderPath, string assetPath, out string resolvedPath)
    {
        resolvedPath = string.Empty;
        if (!IsSafeRelativeAssetPath(assetPath))
        {
            return false;
        }

        try
        {
            var folderRoot = Path.GetFullPath(folderPath);
            var folderPrefix = Path.EndsInDirectorySeparator(folderRoot)
                ? folderRoot
                : folderRoot + Path.DirectorySeparatorChar;
            var candidate = Path.GetFullPath(Path.Combine(
                folderRoot,
                assetPath.Replace('/', Path.DirectorySeparatorChar)));
            if (!candidate.StartsWith(folderPrefix, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }

            resolvedPath = candidate;
            return true;
        }
        catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }
}
