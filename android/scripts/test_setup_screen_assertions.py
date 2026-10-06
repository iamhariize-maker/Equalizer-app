import unittest
from xml.etree.ElementTree import fromstring
from assert_setup_screen import assert_screen


def screen(button_height=48, text_bottom=616, extra=""):
    return fromstring(f'''<hierarchy><node clickable="false">
      <node text="UI test fixture · not live detection" bounds="[16,24][225,37]"/>
      <node clickable="true" enabled="true" bounds="[16,580][304,{580 + button_height}]">
        <node text="Back to Svan" enabled="true" bounds="[116,592][204,{text_bottom}]"/>
      </node>{extra}</node></hierarchy>''')


class SetupScreenAssertionsTest(unittest.TestCase):
    def test_text_node_height_is_not_the_button_height(self):
        assert_screen(screen(), "install", 160, 640)

    def test_clipped_button_or_text_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "clipped"):
            assert_screen(screen(button_height=24, text_bottom=602), "install", 160, 640)
        with self.assertRaisesRegex(AssertionError, "clipped"):
            assert_screen(screen(text_bottom=628), "install", 160, 640)

    def test_disabled_control_is_read_from_parent_not_enabled_text(self):
        extra='''<node text="Enabling detection…" enabled="true"/>
          <node clickable="true" enabled="false">
            <node text="Enabling detection…" enabled="true"/>
          </node>'''
        assert_screen(screen(extra=extra), "working", 160, 640)
        with self.assertRaisesRegex(AssertionError, "progress"):
            assert_screen(screen(extra=extra.replace('enabled="false"', 'enabled="true"')),
                          "working", 160, 640)

    def test_status_heading_cannot_overlap_banner(self):
        with self.assertRaisesRegex(AssertionError, "overlaps"):
            assert_screen(screen(extra='<node text="Is it working?" bounds="[16,25][150,45]"/>'),
                          "status-idle", 160, 640)


if __name__ == "__main__":
    unittest.main()
