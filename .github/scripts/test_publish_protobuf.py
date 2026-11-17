import gzip
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import index_pb2
from publish_protobuf import build_catalog


class PublishProtobufTest(unittest.TestCase):
    def setUp(self):
        self.metadata = {
            "name": "Project Nox Anime",
            "shortName": "Nox Anime",
            "website": "https://github.com/ProjectNox-org/anime-extensoes",
            "signingKeyFingerprint": "144f44e865cbfae6bde110489ef7ec142f0b1681f627a093a218650e21db5dad",
        }
        self.catalog = [
            {
                "name": "Aniyomi: Torrent",
                "pkg": "eu.kanade.tachiyomi.animeextension.all.torrent",
                "apk": "aniyomi-all.torrent-v14.11.apk",
                "lang": "all",
                "code": 11,
                "version": "14.11",
                "nsfw": 1,
                "torrent": True,
                "sources": [
                    {
                        "name": "Torrent",
                        "lang": "en",
                        "id": "123",
                        "baseUrl": "https://example.org",
                    },
                    {
                        "name": "Empty Home",
                        "lang": "all",
                        "id": "124",
                        "baseUrl": "",
                    },
                ],
            }
        ]

    def test_builds_deterministic_official_anime_index(self):
        first = build_catalog(self.catalog, self.metadata)
        second = build_catalog(self.catalog, self.metadata)

        self.assertEqual(first, second)
        self.assertEqual(first[:10], b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x02\x03")

        index = index_pb2.Index()
        index.ParseFromString(gzip.decompress(first))
        self.assertEqual(index.name, "Project Nox Anime")
        self.assertEqual(index.badgeLabel, "Nox Anime")
        self.assertEqual(index.signingKey, self.metadata["signingKeyFingerprint"])
        self.assertEqual(len(index.extensionList.extensions), 1)

        extension = index.extensionList.extensions[0]
        self.assertEqual(extension.extensionLib, "14")
        self.assertEqual(extension.versionCode, 1400011)
        self.assertEqual(extension.versionName, "14.11")
        self.assertEqual(extension.contentWarning, index_pb2.CONTENT_WARNING_NSFW)
        self.assertTrue(extension.isTorrent)
        self.assertEqual(
            extension.resources.apkUrl,
            "https://raw.githubusercontent.com/ProjectNox-org/anime-extensoes/repo/apk/"
            "aniyomi-all.torrent-v14.11.apk",
        )
        self.assertEqual(
            extension.resources.iconUrl,
            "https://cdn.jsdelivr.net/gh/ProjectNox-org/anime-extensoes@repo/icon/"
            "eu.kanade.tachiyomi.animeextension.all.torrent.png",
        )
        self.assertEqual(len(extension.sources), 2)
        self.assertEqual(extension.sources[0].homeUrl, "https://example.org")
        self.assertEqual(extension.sources[1].id, 124)
        self.assertEqual(extension.sources[1].homeUrl, "")

    def test_uses_official_aniyomi_field_numbers(self):
        fields = index_pb2.Extension.DESCRIPTOR.fields_by_name
        self.assertEqual(fields["isTorrent"].number, 8)
        self.assertEqual(fields["sources"].number, 9)
        self.assertEqual(
            index_pb2.Source.DESCRIPTOR.fields_by_name["message"].number, 6
        )

    def test_rejects_non_http_source_urls(self):
        self.catalog[0]["sources"][0]["baseUrl"] = "javascript:alert(1)"

        with self.assertRaises(ValueError):
            build_catalog(self.catalog, self.metadata)


if __name__ == "__main__":
    unittest.main()
