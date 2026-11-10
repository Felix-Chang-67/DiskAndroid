# UDF 2.60 + ISO 9660 + Joliet 样本（pycdlib）：日文文件名、多层目录、跨多个扇区的文件
import io
import sys

import pycdlib

iso = pycdlib.PyCdlib()
iso.new(interchange_level=3, joliet=3, udf='2.60', vol_ident='UDF260_JP')


def add_dir(iso_path, joliet_path, udf_path):
    iso.add_directory(iso_path, joliet_path=joliet_path, udf_path=udf_path)


def add_file(data, iso_path, joliet_path, udf_path):
    iso.add_fp(io.BytesIO(data), len(data), iso_path, joliet_path=joliet_path, udf_path=udf_path)


add_dir('/MUSIC', '/音楽', '/音楽')
add_dir('/MUSIC/LIVE', '/音楽/ライブ', '/音楽/ライブ')
add_file(b'setlist\n', '/MUSIC/LIVE/SETLIST.TXT;1', '/音楽/ライブ/セットリスト.txt', '/音楽/ライブ/セットリスト.txt')
big = bytes(((i * 13 + i // 2048) % 253) for i in range(70000))
add_file(big, '/BIG.BIN;1', '/大きい.bin', '/大きい.bin')
add_dir('/BDMV', '/BDMV', '/BDMV')
add_file(b'INDX0200', '/BDMV/INDEX.BDM;1', '/BDMV/index.bdmv', '/BDMV/index.bdmv')
add_file(b'MOBJ0200', '/BDMV/MOVIEOBJ.BDM;1', '/BDMV/MovieObject.bdmv', '/BDMV/MovieObject.bdmv')
iso.write(sys.argv[1])
iso.close()
