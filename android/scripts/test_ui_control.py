import unittest
from xml.etree.ElementTree import fromstring
from ui_control import control_point


class UiControlTest(unittest.TestCase):
    def test_selected_navigation_does_not_need_a_click_action(self):
        root = fromstring('''<hierarchy><node bounds="[0,0][320,640]">
          <node text="Hi-Fi" bounds="[16,55][88,88]"/>
          <node clickable="false" enabled="true" selected="true"
            bounds="[196,560][254,640]">
            <node text="Hi-Fi" bounds="[209,604][241,628]"/>
          </node></node></hierarchy>''')
        self.assertEqual((225, 616), control_point(root, "Hi-Fi"))

    def test_heading_is_not_mistaken_for_navigation(self):
        root = fromstring('''<hierarchy><node bounds="[0,0][320,640]">
          <node text="Hi-Fi" bounds="[16,55][88,88]"/>
          <node clickable="true" enabled="true" bounds="[192,568][256,640]">
            <node text="Hi-Fi" bounds="[209,604][241,628]"/>
          </node></node></hierarchy>''')
        self.assertEqual((225, 616), control_point(root, "Hi-Fi"))

    def test_enabled_text_inside_disabled_button_cannot_be_tapped(self):
        root = fromstring('''<hierarchy><node clickable="true" enabled="false"
          bounds="[0,0][320,80]"><node text="Setting up…" enabled="true"
          bounds="[10,20][180,60]"/></node></hierarchy>''')
        with self.assertRaises(ValueError):
            control_point(root, "Setting up…")

    def test_offscreen_label_cannot_trigger_a_false_success(self):
        root = fromstring('''<hierarchy><node bounds="[0,24][320,560]">
          <node clickable="true" enabled="true" bounds="[16,-80][304,-20]">
            <node text="Keep enhanced detection without Shizuku"
              bounds="[56,-70][264,-30]"/>
          </node></node></hierarchy>''')
        with self.assertRaises(ValueError):
            control_point(root, "Keep enhanced detection without Shizuku")

    def test_tap_stays_inside_the_visible_intersection(self):
        root = fromstring('''<hierarchy><node bounds="[0,24][320,560]">
          <node clickable="true" enabled="true" bounds="[16,0][304,80]">
            <node text="Retry" bounds="[30,0][100,50]"/>
          </node></node></hierarchy>''')
        self.assertEqual((65, 37), control_point(root, "Retry"))


if __name__ == "__main__":
    unittest.main()
