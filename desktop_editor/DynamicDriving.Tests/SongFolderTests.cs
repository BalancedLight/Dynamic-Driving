using DynamicDriving.Core.Manifests;
using Xunit;

namespace DynamicDriving.Tests;

public sealed class SongFolderTests
{
    [Fact]
    public void A_folder_with_no_manifest_is_seeded_from_the_wav_files_it_holds()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "lead_guitar.wav", TestAudio.Tone(220, 500));
        TestAudio.WriteWav(folder.Path, "bass.wav", TestAudio.Tone(110, 500));

        var song = SongFolder.Open(folder.Path);

        Assert.False(song.ManifestExisted);
        Assert.Equal(new[] { "bass.wav", "lead_guitar.wav" }, song.WavFiles);
        Assert.Equal(new[] { "bass", "lead_guitar" }, song.Manifest.Stems.Select(stem => stem.StemId));
        Assert.Equal("Bass", song.Manifest.Stems[0].DisplayName);
        Assert.Equal("Lead Guitar", song.Manifest.Stems[1].DisplayName);
        Assert.Equal("bass", song.Manifest.TransportStemId);
    }

    [Fact]
    public void Saving_writes_a_manifest_that_reopens_identically()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "main.wav", TestAudio.Tone(220, 2_000));

        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "main.wav" });
        manifest.Artist = "Contract Test Artist";
        manifest.Album = "Contract Test Album";
        manifest.LoopRegion.EndMs = 1_500;

        SongFolder.Save(manifest, folder.Path);
        var reopened = SongFolder.Open(folder.Path);

        Assert.True(reopened.ManifestExisted);
        Assert.Equal("Contract Test Artist", reopened.Manifest.Artist);
        Assert.Equal("Contract Test Album", reopened.Manifest.Album);
        Assert.Equal(1_500, reopened.Manifest.LoopRegion.EndMs);
    }

    [Fact]
    public void An_invalid_manifest_never_overwrites_the_one_on_disk()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "main.wav", TestAudio.Tone(220, 2_000));

        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "main.wav" });
        manifest.LoopRegion.EndMs = 1_500;
        SongFolder.Save(manifest, folder.Path);
        var goodJson = File.ReadAllText(Path.Combine(folder.Path, SongFolder.ManifestFileName));

        var broken = SongFolder.Open(folder.Path).Manifest;
        broken.TransportStemId = "a_stem_that_does_not_exist";

        Assert.Throws<InvalidOperationException>(() => SongFolder.Save(broken, folder.Path));
        Assert.Equal(goodJson, File.ReadAllText(Path.Combine(folder.Path, SongFolder.ManifestFileName)));
    }

    [Fact]
    public void A_loop_past_the_end_of_the_audio_is_rejected_before_anything_is_written()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "main.wav", TestAudio.Tone(220, 1_000));

        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "main.wav" });
        manifest.LoopRegion.EndMs = 9_000;

        Assert.Throws<InvalidOperationException>(() => SongFolder.Save(manifest, folder.Path));
        Assert.False(File.Exists(Path.Combine(folder.Path, SongFolder.ManifestFileName)));
    }

    [Fact]
    public void Saving_leaves_no_temporary_files_behind()
    {
        using var folder = new TempFolder();
        TestAudio.WriteWav(folder.Path, "main.wav", TestAudio.Tone(220, 2_000));
        var manifest = SongFolder.CreateDefaultManifest(folder.Path, new[] { "main.wav" });
        manifest.LoopRegion.EndMs = 1_000;

        SongFolder.Save(manifest, folder.Path);
        SongFolder.Save(manifest, folder.Path);

        Assert.Empty(Directory.EnumerateFiles(folder.Path, "*.tmp"));
    }

    [Fact]
    public void Cover_art_is_discovered_and_can_be_replaced()
    {
        using var folder = new TempFolder();
        using var source = new TempFolder();
        var sourceImage = source.File("art.png");
        File.WriteAllBytes(sourceImage, new byte[] { 0x89, 0x50, 0x4E, 0x47 });

        Assert.Null(SongFolder.Open(EnsureSong(folder)).CoverPath);

        SongFolder.ImportCover(folder.Path, sourceImage);

        var coverPath = SongFolder.Open(folder.Path).CoverPath;
        Assert.NotNull(coverPath);
        Assert.Equal("cover.png", Path.GetFileName(coverPath));

        var replacementImage = source.File("replacement.jpg");
        File.WriteAllBytes(replacementImage, new byte[] { 0xFF, 0xD8, 0xFF, 0xD9 });
        SongFolder.ImportCover(folder.Path, replacementImage);

        Assert.False(File.Exists(folder.File("cover.png")));
        Assert.Equal("cover.jpg", Path.GetFileName(SongFolder.Open(folder.Path).CoverPath));
    }

    [Fact]
    public void Importing_the_current_cover_keeps_it_intact()
    {
        using var folder = new TempFolder();
        var coverPath = folder.File("cover.png");
        var expectedBytes = new byte[] { 0x89, 0x50, 0x4E, 0x47 };
        File.WriteAllBytes(coverPath, expectedBytes);

        var importedPath = SongFolder.ImportCover(folder.Path, coverPath);

        Assert.Equal(Path.GetFullPath(coverPath), importedPath);
        Assert.Equal(expectedBytes, File.ReadAllBytes(coverPath));
    }

    [Fact]
    public void A_failed_cover_copy_preserves_the_existing_cover()
    {
        using var folder = new TempFolder();
        var coverPath = folder.File("cover.png");
        var expectedBytes = new byte[] { 0x89, 0x50, 0x4E, 0x47 };
        File.WriteAllBytes(coverPath, expectedBytes);
        var missingReplacement = folder.File("missing.jpg");

        Assert.Throws<FileNotFoundException>(() => SongFolder.ImportCover(folder.Path, missingReplacement));

        Assert.Equal(expectedBytes, File.ReadAllBytes(coverPath));
        Assert.Equal("cover.png", Path.GetFileName(SongFolder.Open(folder.Path).CoverPath));
    }

    [Fact]
    public void Importing_an_unsupported_image_is_refused()
    {
        using var folder = new TempFolder();
        using var source = new TempFolder();
        var sourceImage = source.File("art.bmp");
        File.WriteAllBytes(sourceImage, new byte[] { 0x42, 0x4D });

        Assert.Throws<NotSupportedException>(() => SongFolder.ImportCover(folder.Path, sourceImage));
    }

    [Fact]
    public void Identifiers_and_labels_are_derived_predictably()
    {
        Assert.Equal("lead_guitar", SongFolder.SanitizeIdentifier("Lead Guitar"));
        Assert.Equal("open_road", SongFolder.SanitizeIdentifier("Open  Road!"));
        Assert.Equal("stem", SongFolder.SanitizeIdentifier("***"));
        Assert.Equal("Lead Guitar", SongFolder.ToDisplayLabel("lead_guitar"));
        Assert.Equal("Open Road", SongFolder.ToDisplayLabel("open-road"));
    }

    private static string EnsureSong(TempFolder folder)
    {
        TestAudio.WriteWav(folder.Path, "main.wav", TestAudio.Tone(220, 500));
        return folder.Path;
    }
}
