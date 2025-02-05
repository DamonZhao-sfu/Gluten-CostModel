#include <arrow/api.h>
#include <arrow/io/api.h>
#include <arrow/ipc/api.h>
#include <arrow/util/iterator.h>
#include <arrow/pretty_print.h>

#include <iostream>
#include <fstream>
#include <vector>
#include <bitset>
#include <stdexcept>
#include <algorithm>
#include <map>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <stdio.h>
#include <aio.h>
#include <fcntl.h>
#include <chrono>
#include <numeric> 
#include <thread>
#include <arrow/api.h>
#include <cstddef> 
#include <immintrin.h>


#include <orc/orc-config.hh>
#include <orc/Reader.hh>
#include <orc/Exceptions.hh>
#include <orc/OrcFile.hh>

//leave this space between tapa.h and ap_int.h
#include <ap_int.h>
#include <gflags/gflags.h>
#include <glog/logging.h>

// #define __CL_ENABLE_EXCEPTIONS/ #include <CL/cl.h>

#include <tinyxml.h>
#include <xclbin.h>
#define CL_HPP_ENABLE_EXCEPTIONS
// #define CL_HPP_TARGET_OPENCL_VERSION 200
#include <CL/cl2.hpp>

#include <CL/opencl.h>
//#include <CL/cl_ext_xilinx.h>

#define CL_DEVICE_PCIE_BDF              0x1120  // BUS/DEVICE/FUNCTION
#include "opencl_util.h"


extern "C" {
    int aio_write(struct aiocb*);
    int aio_read(struct aiocb*);
    int aio_error(const struct aiocb *aiocbp);
    ssize_t aio_return(struct aiocb *aiocbp);
    int aio_suspend(const struct aiocb * const cblist[], int n, const struct timespec *timeout);
}

#define WAIT_MAX 2147483
// #define PRINT_DEBUG

int nvmeFd = -1;
const uint32_t AXI_WIDTH = 512;
const uint16_t AXI_WIDTH_HH = 128;
typedef ap_uint<AXI_WIDTH> _512b;
typedef ap_int<AXI_WIDTH> _512bi;
typedef ap_uint<AXI_WIDTH_HH> _128b;
typedef ap_uint<32> _32b;

const uint32_t BUFFERS_IN = 2;
const uint32_t BUFFERS_OUT = 10;
const uint32_t ALIGNED_BYTES = 4096;
// const std::string TARGET_DEVICE_NAME = "xilinx_u280_xdma_201920_3";

bool dataflow = true;
bool dataVerif = true;
const uint32_t RSIZE_DIV = 16;   //for SR it should be 4 else 16
const uint32_t PIPELINE_DEPTH = 576;
uint32_t nrows = 0;
std::string orc_file = "/localssd/hza214/80M.orc";
std::string check_file = "lineitem_col1.bin"; 

const uint8_t SR = 0;
const uint8_t DIRECT = 1;
const uint8_t PATCHED = 2;
const uint8_t DELTA = 3;


class FPGARecordBatchIterator {
public:
    FPGARecordBatchIterator(uint8_t* data0, uint8_t* data1, uint8_t* data2, uint8_t* data3, uint32_t* filterRowCount, uint32_t T_ITER,
                        uint32_t* stripe_rows, uint32_t stripe_count, int batch_size = 4096)
        : data0_(data0), data1_(data1), data2_(data2), data3_(data3),
          stripe_rows_(stripe_rows), stripe_count_(stripe_count),
          batch_size_(batch_size), stCount_(0), offset_(0) {}

    arrow::Result<std::shared_ptr<arrow::RecordBatch>> Next() {
        if (stCount_ >= stripe_count_) {
            return nullptr;  // No more batches
        }

        // Read the total number of rows in the current stripe
        int kernel_dout = 0;
        ap_uint<AXI_WIDTH> buf_out = 0;
        std::vector<int32_t> combinedData;

        int32_t* mData[4];

        mData[0] = reinterpret_cast<int32_t*>(data0);
        mData[1] = reinterpret_cast<int32_t*>(data1);
        mData[2] = reinterpret_cast<int32_t*>(data2);
        mData[3] = reinterpret_cast<int32_t*>(data3);
        // Process data in sequence until tRows or until the batch is filled
        
        for(int j = 0; j < T_ITER; j++)
        {
            uint32_t remDiv = filterRowCount[j]%64;
            uint64_t dOut_Size_A = filterRowCount[j]/64;   //I THINK ITS SOLVED FOR SR NOW.bcz of short repeat. else its nrows/16. One 512 can worst case contain 3 numbers.
            if(remDiv!=0)
            {
                dOut_Size_A += 1;
            }
            // dOut_Size_A = dOut_Size_A*4;
            std::cout << "dOut_Size_A:  " << dOut_Size_A << std::endl;

            for(int i = 0; i < (dOut_Size_A); i ++)
            {
                for(int k = 0; k < 4; k++)
                {
                    for(int z = 0; z < 16; z++)
                    {
                        kernel_dout = mData[k][(i*16)+z];
                        // std::cout << "kernel_dout: " << kernel_dout << std::endl;
                        combinedData.push_back(kernel_dout);
                    }
                }   
            }
        }

        if (combined_data.empty()) {
            return nullptr;  // No more data
        }

        // Create an Arrow Array from the collected data
        auto buffer = std::make_shared<arrow::Buffer>(reinterpret_cast<uint8_t*>(combined_data.data()), combined_data.size() * sizeof(int32_t));
        auto array_data = arrow::ArrayData::Make(arrow::int32(), combined_data.size(), {nullptr, buffer});
        auto array = arrow::MakeArray(array_data);

        // Create a RecordBatch with the array
        return arrow::RecordBatch::Make(schema_, array->length(), {array});
    }

private:
    uint8_t* data0_;
    uint8_t* data1_;
    uint8_t* data2_;
    uint8_t* data3_;
    uint32_t* stripe_rows_;
    uint32_t stripe_count_;
    int batch_size_;
    uint32_t stCount_;
    int offset_;
    
    std::shared_ptr<arrow::Schema> schema_ = arrow::schema({arrow::field("column1", arrow::int32())});
};

