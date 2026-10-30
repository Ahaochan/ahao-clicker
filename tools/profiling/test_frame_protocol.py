import unittest
from frame_protocol import fields, audit_event, trace_records


def varint(value):
    value &= (1<<64)-1
    out=bytearray()
    while value>127:out.append((value&127)|128);value>>=7
    out.append(value);return bytes(out)


def pb(number,value):
    return varint(number<<3 | (2 if isinstance(value,bytes) else 0))+(varint(len(value))+value if isinstance(value,bytes) else varint(value))


class ProtocolTests(unittest.TestCase):
    def test_wire_truncation_is_never_ignored(self):
        self.assertEqual(dict(fields(pb(1,17)+pb(2,b'ab'))),{1:17,2:b'ab'})
        for invalid in [b'\x80',pb(1,b'ab')[:-1],b'\x00',b'\x08'+b'\xff'*10]:
            with self.assertRaises(ValueError):list(fields(invalid))

    def test_aux_enum_error_does_not_destroy_structural_frame(self):
        event=dict(kind=4,ts=100,fields={1:10,2:20,3:30,4:40,6:2,10:1,17:0})
        self.assertEqual(audit_event(event),[dict(reason='present_type_experimental',severity='metadata',value=0)])
        event['fields'].pop(4)
        self.assertEqual(audit_event(event)[0]['severity'],'structure')

    def test_negative_time_and_missing_cookie(self):
        self.assertEqual(audit_event(dict(kind=5,ts=-1,fields={1:1}))[0]['reason'],'invalid_timestamp')
        self.assertEqual(audit_event(dict(kind=5,ts=1,fields={}))[0]['severity'],'structure')

    def test_packet_location_and_boot_clock_preserved(self):
        surface=pb(1,10)+pb(2,20)+pb(3,30)+pb(4,40)+pb(6,2)+pb(10,1)
        packet=pb(8,12345)+pb(58,6)+pb(76,pb(4,surface))
        result=list(trace_records(pb(1,packet)))
        self.assertEqual(result[0]['ts'],12345)
        self.assertEqual(result[0]['clockId'],6)
        self.assertEqual(result[0]['fields'][4],40)


if __name__=='__main__':unittest.main()
