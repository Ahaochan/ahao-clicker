import unittest
from restore_v3 import is_installed

class RestoreInventoryTests(unittest.TestCase):
    def test_absent_package_is_a_normal_empty_inventory(self):
        self.assertFalse(is_installed('','com.ahao.clicker.test'))
        self.assertFalse(is_installed('package:com.ahao.clicker\n','com.ahao.clicker.test'))
    def test_package_identity_requires_a_complete_line(self):
        self.assertFalse(is_installed('package:com.ahao.clicker.test.other\n','com.ahao.clicker.test'))
        self.assertTrue(is_installed('package:com.ahao.clicker.test\r\n','com.ahao.clicker.test'))
