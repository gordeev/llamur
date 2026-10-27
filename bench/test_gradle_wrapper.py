"""Pin the wrapper JAR as well as the Gradle distribution it downloads."""
import hashlib
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class GradleWrapperTests(unittest.TestCase):
    def test_wrapper_matches_gradle_8_14_3(self):
        # Official checksums: https://services.gradle.org/distributions/
        wrapper = ROOT / 'gradle/wrapper'
        self.assertEqual(
            '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172',
            hashlib.sha256((wrapper / 'gradle-wrapper.jar').read_bytes()).hexdigest())
        properties = dict(line.split('=', 1) for line in
                          (wrapper / 'gradle-wrapper.properties').read_text().splitlines()
                          if line and not line.startswith('#'))
        self.assertEqual(r'https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip',
                         properties['distributionUrl'])
        self.assertEqual('bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531',
                         properties['distributionSha256Sum'])
        self.assertEqual('true', properties['validateDistributionUrl'])
