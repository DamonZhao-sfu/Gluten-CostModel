/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include <arrow/adapters/orc/adapter.h>
#include "benchmarks/common/FileReaderIterator.h"
#include "benchmarks/common/FPGARecordBatchIterator.h"

namespace gluten {

extern "C" {
    int aio_write(struct aiocb*);
    int aio_read(struct aiocb*);
    int aio_error(const struct aiocb *aiocbp);
    ssize_t aio_return(struct aiocb *aiocbp);
    int aio_suspend(const struct aiocb * const cblist[], int n, const struct timespec *timeout);
}

typedef ap_uint<AXI_WIDTH_H> _256b;
typedef ap_uint<AXI_WIDTH> _512b;
typedef ap_int<AXI_WIDTH> _512bi;
typedef ap_uint<AXI_WIDTH_HH> _128b;
typedef ap_uint<32> _32b;


class OrcReaderIterator : public FileReaderIterator {
 public:
  explicit OrcReaderIterator(const std::string& path) : FileReaderIterator(path) {}

  void createReader() override {
    // Open File
    auto input = arrow::io::ReadableFile::Open(path_);
    GLUTEN_THROW_NOT_OK(input);

    // Open ORC File Reader
    auto maybeReader = arrow::adapters::orc::ORCFileReader::Open(*input, arrow::default_memory_pool());
    GLUTEN_THROW_NOT_OK(maybeReader);
    fileReader_.reset((*maybeReader).release());

    // get record batch Reader
    auto recordBatchReader = fileReader_->GetRecordBatchReader(4096, std::vector<std::string>());
    GLUTEN_THROW_NOT_OK(recordBatchReader);
    recordBatchReader_ = *recordBatchReader;
  }

  std::shared_ptr<arrow::Schema> getSchema() override {
    auto schema = fileReader_->ReadSchema();
    GLUTEN_THROW_NOT_OK(schema);
    return *schema;
  }

 protected:
  std::unique_ptr<arrow::adapters::orc::ORCFileReader> fileReader_;
  std::shared_ptr<arrow::RecordBatchReader> recordBatchReader_;
};


class FORCReaderIterator final : public OrcReaderIterator {
  private:

    const uint32_t NDelta_BitMap[32] = {1,  2,  3,  4,  5,  6,  7,  8,  9,  10, 11,
                                12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
                                23, 24, 26, 28, 30, 32, 40, 48, 56, 64};
    const uint32_t AXI_WIDTH = 512;
    const uint32_t AXI_BYTES = 512/8;
    const uint16_t AXI_WIDTH_H = 256;
    const uint16_t AXI_WIDTH_HH = 128;
    // #define PRINT_DEBUG
    const bool dataflow = 1;        //**********DATAFLOW FLAG***************//
    const uint32_t DATA_MUL = 1;

    const uint32_t BUFFERS_IN = 2;
    const uint32_t BUFFERS_OUT = 12;
    const uint32_t ALIGNED_BYTES = 4096;

    const uint32_t Myrows = 855000; 
    const uint64_t FILE_CHUNKS = 1;
    const uint64_t OFFSET_MUL = 0;  //TURN OFF OFFSET IF want to read same data always

    const uint8_t SR = 0;
    const uint8_t DIRECT = 1;
    const uint8_t PATCHED = 2;
    const uint8_t DELTA = 3;
    void async_readnorm(struct aiocb* aio_rf, void* data_in, int Fd, int vector_size_bytes, int offset)
    {
        aio_rf->aio_buf = data_in;
        aio_rf->aio_fildes = Fd;
        aio_rf->aio_nbytes = vector_size_bytes;
        aio_rf->aio_offset = offset;
        int result = aio_read(aio_rf);
        if (result < 0)
        {
            printf("Read Failed: %d \n", result);
        }
    }

    void copy_data(unsigned char* src, unsigned char* dest, size_t size, size_t offset) {
        unsigned char* src_ptr = src;
        unsigned char* dest_ptr = dest + offset;
        for (size_t i = 0; i < size; ++i) {
            dest_ptr[i] = src_ptr[i];
        }
    }
    
  public: FORCReaderIterator(const std::string& path) : OrcReaderIterator(path) {

    uint32_t file_size_rem = 0;
    uint32_t KRNL_file_size_bytes = 0;
    std::vector<uint32_t> Data_offsets;
    std::vector<uint32_t> Data_lengths;
    std::vector<uint32_t> stripe_rows;  // Array to store the number of rows in each stripe
    uint32_t Data_offset = 0;
    uint32_t KRNL_Data_Write = 0;
    uint32_t wait_count = 32;  //max FIFO depth for hardware and try 2147483 for csim

    uint32_t max_stripe_rows = 0;  // Variable to store the maximum number of rows in any stripe
    uint32_t max_data_length = 0;  // Variable to store the maximum data length
    uint32_t total_data_length = 0;  // Variable to store the sum of all data lengths


    orc::ReaderOptions readerOpts;
    std::unique_ptr<orc::Reader> reader =
        orc::createReader(orc::readFile(path, readerOpts.getReaderMetrics()), readerOpts);
    std::string filePath = path;
    std::string nvme_file = filePath;
    int nvmeFd = open(nvme_file.c_str(), O_RDONLY); //O_SYNC O_DIRECT  O_RDONLY  O_RDWR
    if (nvmeFd < 0) {
        std::cerr << "ERROR: open " << nvme_file << "failed: " << std::endl;
    }
    uint32_t numberColumns = reader->getType().getMaximumColumnId() + 1;
    uint32_t nrows = reader->getNumberOfRows();
    uint32_t stripeCount = reader->getNumberOfStripes();

    for (uint32_t col = 0; col < numberColumns; ++col) {
        orc::ColumnEncodingKind encoding = reader->getStripe(0)->getColumnEncoding(col);
    }


    for (uint32_t str = 0; str < stripeCount; ++str) {
      auto stripe = reader->getStripe(str);
      stripe_rows.push_back(stripe->getNumberOfRows());  // Populate the stripe_rows array
      // std::cout << "Stripe " << str << " has " << stripe->getNumberOfRows() << " rows.\n";  // Print the number of rows in each stripe
      for (uint32_t streamIdx = 0; streamIdx < stripe->getNumberOfStreams(); ++streamIdx) {
        std::unique_ptr<orc::StreamInformation> stream = stripe->getStreamInformation(streamIdx);
        if (stream->getKind() == 1) {
            // std::cout << "Data stream found" << std::endl;
            Data_offsets.push_back(stream->getOffset());
            Data_lengths.push_back(stream->getLength());
        }
      }
    }



    // Finding the maximum values
    if (!stripe_rows.empty()) {
        max_stripe_rows = static_cast<uint32_t>(*std::max_element(stripe_rows.begin(), stripe_rows.end()));
    } else {
        std::cout << "stripe_rows vector is empty." << std::endl;
    }

    if (!Data_lengths.empty()) {
    max_data_length = *std::max_element(Data_lengths.begin(), Data_lengths.end());
    total_data_length = std::accumulate(Data_lengths.begin(), Data_lengths.end(), 0);
    } else {
        std::cout << "Data_lengths vector is empty." << std::endl;
    }
    reader.reset();

    std::cout << "Total Stripes: " << stripeCount << std::endl;
    std::cout << "Maximum number of rows in any stripe: " << max_stripe_rows << std::endl;
    std::cout << "Maximum data length: " << max_data_length << std::endl;
    std::cout << "Total data length: " << total_data_length << std::endl;
    std::cout << "Total rows: " << nrows << std::endl;

    uint8_t* data_in_HBM[BUFFERS_IN];           //input port
    uint8_t* FilterConf_HBM[BUFFERS_IN];           //Filter Conf 
    uint8_t* data_out_HBM[BUFFERS_OUT];         //4 output, 1 meta and 1 idx    = 5*2 = 12

    uint8_t* dataOut[4]; 
    uint8_t* trackOut;
    
    uint32_t max_input_size = max_data_length+PIPELINE_DEPTH+64;
    uint32_t max_output_size = max_stripe_rows; //MAX OUTPUT SIZE BYTES = (max_stripe_rows*4) , div 4 as data is divided in 4 ports 
    uint32_t max_track_size = max_stripe_rows*1; //max it can be 2x of the one data port size

    uint32_t trackRem = max_track_size%16;
    if(trackRem != 0)       //128bit is 16bytes
    {
        max_track_size += (16 - trackRem);
    }

    uint32_t total_track_size = nrows*1; 
    trackRem = total_track_size%16;
    if(trackRem != 0)       //128bit is 16bytes
    {
        total_track_size += (16 - trackRem);
    }

    uint32_t filterConfSize = 192;
    for(int i = 0; i < BUFFERS_IN; i++)
    {
        data_in_HBM[i] = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, max_input_size));
    }
    for(int i = 0; i < BUFFERS_IN; i++)
    {
        FilterConf_HBM[i] = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, 192));
    }
    //out ports and idx port
    for (int i = 0; i < BUFFERS_OUT-2; ++i) {
        data_out_HBM[i] = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, max_output_size));
    }
    //track ports
    for (int i = BUFFERS_OUT-2; i < BUFFERS_OUT; ++i) {
        data_out_HBM[i] = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, max_track_size));
    }

    //Data ports for complete data
    //out ports
    for (int i = 0; i < 4; ++i) {
        dataOut[i] = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, (max_output_size*DATA_MUL)));
    }
    //track port
    trackOut = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, (max_track_size*DATA_MUL)));
    idxOut = static_cast<uint8_t*>(aligned_alloc(ALIGNED_BYTES, (max_output_size*DATA_MUL)));
    filterRowCount = static_cast<uint32_t*>(aligned_alloc(ALIGNED_BYTES, (stripeCount*DATA_MUL)*192));

     //Write Filter Config
    uint8_t idx_flag = 0;           //use filter condition(0),  use stored idx(1)
    uint8_t range_flag = 0;         //either use both ranges(1), or only right range(0) , Currently unused (using both in ranges in kernel)
    uint8_t RROP = 0;
    uint8_t LROP = 0;
    int32_t RR = 0;
    int32_t LR = 0;

    RROP = 2;   // less than(1), less than equal to(2)
    LROP = 6;   //  5 = greater than, 6 = greater than equal
    RR = FLAGS_RR;   //SR->300, DD->100, max 4294967295, ...100000000<#<330000000 ... LR<#<RR ... 4294967295 ... 2147483647
    LR = 0;    //SR->10, DD->30

    _512b filconf = (uint32_t)(LR);
    filconf = filconf << 32;    //4
    filconf = filconf | (uint32_t)(RR);
    filconf = filconf << 8;     //1
    filconf = filconf | LROP;
    filconf = filconf << 8;     //1
    filconf = filconf | RROP;
    filconf = filconf << 8;     //1
    filconf = filconf | range_flag;
    filconf = filconf << 8;     //1
    filconf = filconf | idx_flag;

    uint32_t MyremDiv = nrows%64;
    uint32_t readCnt = nrows/64;
    if(MyremDiv!=0)
    {
        readCnt += 1;
    }

    // Cast the FilterConf_HBM pointer to an ap_uint<512>* to store filconf
    _512b* ptr = reinterpret_cast<ap_uint<512>*>(FilterConf_HBM[0]);
    _512b* ptr1 = reinterpret_cast<ap_uint<512>*>(FilterConf_HBM[1]);

    // Assign the first 512 bits (64 bytes) to 'filconf'
    ptr[0] = filconf;
    ptr1[0] = filconf;

    // The second 512 bits will store the 'readCnt'. We'll place 'readCnt' in the first 32 bits of this block.
    _512b readCntBlock = 0;
    readCntBlock.range(31, 0) = readCnt;

    // Assign the second 512 bits (64 bytes) to 'readCnt'
    ptr[1] = readCntBlock;
    ptr1[1] = readCntBlock;

    /////////MY HOST///////////
    cl::Device device_;
    cl::Context context_;
    cl::CommandQueue cmd_;
    cl::Program program_;
    std::string My_device_name;
    std::map<int, cl::Kernel> kernels_;

    std::string target_device_name;
    std::vector<std::string> kernel_names;
    std::vector<int> kernel_arg_counts;
    int arg_count = 0;

    cl::Program::Binaries binaries;
    std::ifstream stream("/localhdd/hza214/FORC-compress/FORC/xclbin/FORC.xclbin", std::ios::binary);
    binaries = {{std::istreambuf_iterator<char>(stream),
                            std::istreambuf_iterator<char>()}};
    
    const auto axlf_top = reinterpret_cast<const axlf*>(binaries.begin()->data());
    switch (axlf_top->m_header.m_mode) {
        case XCLBIN_FLAT:
        case XCLBIN_PR:
        case XCLBIN_TANDEM_STAGE2:
        case XCLBIN_TANDEM_STAGE2_WITH_PR:
        break;
        case XCLBIN_HW_EMU:
        setenv("XCL_EMULATION_MODE", "hw_emu", 0);
        break;
        case XCLBIN_SW_EMU:
        setenv("XCL_EMULATION_MODE", "sw_emu", 0);
        break;
        default:
        LOG(FATAL) << "Unknown xclbin mode";
    }
    target_device_name =
        reinterpret_cast<const char*>(axlf_top->m_header.m_platformVBNV);
    std::cout << "target_device_name: " << target_device_name << std::endl;
    if (auto metadata = xclbin::get_axlf_section(axlf_top, EMBEDDED_METADATA)) {
        TiXmlDocument doc;
        doc.Parse(
            reinterpret_cast<const char*>(axlf_top) + metadata->m_sectionOffset,
            nullptr, TIXML_ENCODING_UTF8);
        auto xml_core = doc.FirstChildElement("project")
                            ->FirstChildElement("platform")
                            ->FirstChildElement("device")
                            ->FirstChildElement("core");
        std::string target_meta = xml_core->Attribute("target");
        for (auto xml_kernel = xml_core->FirstChildElement("kernel");
            xml_kernel != nullptr;
            xml_kernel = xml_kernel->NextSiblingElement("kernel")) 
        {
            kernel_names.push_back(xml_kernel->Attribute("name"));
            kernel_arg_counts.push_back(arg_count);
            ++arg_count;
            size_t i = kernel_names.size() - 1;
            std::cout << "Kernel Name: " << kernel_names[i] << ", Argument Count: " << kernel_arg_counts[i] << std::endl;
        }
        if (target_meta == "hw_em") {
        setenv("XCL_EMULATION_MODE", "hw_emu", 0);
        } else if (target_meta == "csim") {
        setenv("XCL_EMULATION_MODE", "sw_emu", 0);
        }
    }
    else {
        LOG(FATAL) << "Cannot determine kernel name from binary";
    }
    if (const char* xcl_emulation_mode = getenv("XCL_EMULATION_MODE")) {
        LOG(FATAL) << "Cannot RUN EMU MODE"; 
    }
    else {
        LOG(INFO) << "Running on-board execution with Xilinx OpenCL";
    }

    std::vector<cl::Platform> platforms;
    CL_CHECK(cl::Platform::get(&platforms));
    cl_int err;
    for (const auto& platform : platforms) {
        std::string platformName = platform.getInfo<CL_PLATFORM_NAME>(&err);
        CL_CHECK(err);
        LOG(INFO) << "Found platform: " << platformName.c_str();
        if (platformName == "Xilinx") {
            std::vector<cl::Device> devices;
            CL_CHECK(platform.getDevices(CL_DEVICE_TYPE_ACCELERATOR, &devices));
            for (const auto& device : devices) {
                const std::string device_name = device.getInfo<CL_DEVICE_NAME>();
                char bdf[32];
                size_t bdf_size = 0;
                CL_CHECK(clGetDeviceInfo(device.get(), CL_DEVICE_PCIE_BDF, sizeof(bdf), bdf,
                                        &bdf_size));
                LOG(INFO) << "Found device: " << device_name;
                if(device_name == target_device_name)
                {
                    My_device_name = device_name;
                    device_ = device;
                    break;
                }
            }

            LOG(INFO) << "Using " << My_device_name;
            context_ = cl::Context(device_, nullptr, nullptr, nullptr, &err);
            if (err == CL_DEVICE_NOT_AVAILABLE) {
                LOG(WARNING) << "Device '" << My_device_name << "' not available";
                continue;
            }
            CL_CHECK(err);
            cmd_ = cl::CommandQueue(context_, device_, CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE | CL_QUEUE_PROFILING_ENABLE, &err);
            CL_CHECK(err);
            
            std::vector<int> binary_status;
            program_ =
                cl::Program(context_, {device_}, binaries, &binary_status, &err);
            for (auto status : binary_status) {
                CL_CHECK(status);
            }
            CL_CHECK(err);
            CL_CHECK(program_.build());
            for (int i = 0; i < kernel_names.size(); ++i) {
                // std::cout << "Kernels Count: " << kernel_arg_counts[i] << std::endl;
                kernels_[kernel_arg_counts[i]] =
                    cl::Kernel(program_, kernel_names[i].c_str(), &err);
                CL_CHECK(err);
            }
        }
        else
        {
            LOG(FATAL) << "Target platform 'Xilinx' not found";
        }
    }
    size_t map_size = kernels_.size();
    std::cout << "Kernels Size: " << map_size << std::endl;
    std::cout << "Kernel Programmed " << std::endl;
    ///////////////////////////

    ///////DECLARE BUFFERS FOR KERNEL////////
    cl::Buffer buffer_in_HBM[BUFFERS_IN];
    cl_mem_ext_ptr_t mIN_HBM[BUFFERS_IN];

    cl::Buffer filConf_in_HBM[BUFFERS_IN];
    cl_mem_ext_ptr_t mFCIN_HBM[BUFFERS_IN];

    cl::Buffer buffer_out_HBM[BUFFERS_OUT];
    cl_mem_ext_ptr_t mOUT_HBM[BUFFERS_OUT];

    //HBM Bank location start from 16
    for(uint32_t i = 0; i < BUFFERS_IN; i ++)
    {
        mIN_HBM[i] = {XCL_MEM_TOPOLOGY | (unsigned int)(i+16), data_in_HBM[i], 0};
        buffer_in_HBM[i] = cl::Buffer(context_, CL_MEM_EXT_PTR_XILINX | CL_MEM_USE_HOST_PTR | CL_MEM_READ_ONLY,
                        (size_t)(max_input_size), &mIN_HBM[i], &err);     // CL_MEM_WRITE_ONLY, CL_MEM_READ_ONLY, CL_MEM_READ_WRITE
        CL_CHECK(err);
    }

    //Filter Conf
    for(uint32_t i = 0; i < BUFFERS_IN; i ++)
    {
        mFCIN_HBM[i] = {XCL_MEM_TOPOLOGY | (unsigned int)(i), FilterConf_HBM[i], 0};
        filConf_in_HBM[i] = cl::Buffer(context_, CL_MEM_EXT_PTR_XILINX | CL_MEM_USE_HOST_PTR | CL_MEM_READ_WRITE,
                        (size_t)(192), &mFCIN_HBM[i], &err);     // CL_MEM_WRITE_ONLY, CL_MEM_READ_ONLY, CL_MEM_READ_WRITE
        CL_CHECK(err);
    }

    for(int i = 0; i < BUFFERS_OUT-4; i++)
    {
        // (XCL_MEM_TOPOLOGY | memory bank)
        mOUT_HBM[i] = {XCL_MEM_TOPOLOGY | (unsigned int)(i+2), data_out_HBM[i], 0};
        buffer_out_HBM[i] = cl::Buffer(context_, CL_MEM_EXT_PTR_XILINX | CL_MEM_USE_HOST_PTR | CL_MEM_WRITE_ONLY,
                        (size_t)(max_output_size), &mOUT_HBM[i], &err);     // CL_MEM_WRITE_ONLY, CL_MEM_READ_ONLY, CL_MEM_READ_WRITE
        CL_CHECK(err);
    }

    //idx port
    for(int i = BUFFERS_OUT-4; i < BUFFERS_OUT-2; i++)
    {
        // (XCL_MEM_TOPOLOGY | memory bank)
        mOUT_HBM[i] = {XCL_MEM_TOPOLOGY | (unsigned int)(i+2), data_out_HBM[i], 0};
        buffer_out_HBM[i] = cl::Buffer(context_, CL_MEM_EXT_PTR_XILINX | CL_MEM_USE_HOST_PTR | CL_MEM_READ_WRITE,
                        (size_t)(max_output_size), &mOUT_HBM[i], &err);     // CL_MEM_WRITE_ONLY, CL_MEM_READ_ONLY, CL_MEM_READ_WRITE
        CL_CHECK(err);
    }

    //meta port
    for(int i = BUFFERS_OUT-2; i < BUFFERS_OUT; i++)
    {
        // (XCL_MEM_TOPOLOGY | memory bank)
        mOUT_HBM[i] = {XCL_MEM_TOPOLOGY | (unsigned int)(i+2), data_out_HBM[i], 0};
        buffer_out_HBM[i] = cl::Buffer(context_, CL_MEM_EXT_PTR_XILINX | CL_MEM_USE_HOST_PTR | CL_MEM_WRITE_ONLY,
                        (size_t)(max_track_size), &mOUT_HBM[i], &err);     // CL_MEM_WRITE_ONLY, CL_MEM_READ_ONLY, CL_MEM_READ_WRITE
        CL_CHECK(err);
    }

    std::cout << "Data in buffer size(MB): " << (max_data_length / (1024.0 * 1024.0)) << std::endl;
    std::cout << "Data out buffer size(MB): " << (max_output_size / (1024.0 * 1024.0)) << std::endl;

    cl::Kernel kernelDD;
    uint32_t dCount = Data_length / 64;
    if ((Data_length % 64) != 0) {
        dCount += 1;
    }

    for (const auto& kvp : kernels_) {
        int index = kvp.first; // Get the index (key) of the kernel
        std::cout << "Setting Kernel["<<index<<"] Arg" << std::endl;
        kernelDD = kvp.second; // Get the kernel associated with the index (key)
        kernelDD.setArg(0, buffer_in_HBM[0]);
        kernelDD.setArg(1, filConf_in_HBM[0]);
        kernelDD.setArg(2, buffer_out_HBM[0]);          //data0
        kernelDD.setArg(3, buffer_out_HBM[2]);          //data1
        kernelDD.setArg(4, buffer_out_HBM[4]);          //data2
        kernelDD.setArg(5, buffer_out_HBM[6]);          //data3
        kernelDD.setArg(6, buffer_out_HBM[8]);          //idx
        kernelDD.setArg(7, buffer_out_HBM[10]);         //data4(meta)
        kernelDD.setArg(8, sizeof(dCount), &dCount);
    }
    std::cout << "Kernels Argument Set." << std::endl;
    
    int ret_aio = 0;
    struct aiocb aio_rf;
    struct aiocb aio_rf1;
    ///////Launching KERNEL SINGLE SHOT////////
    std::vector<cl::Event> kernel_events(3);
    std::vector<cl::Event> kernel_wait_events;

    memset(data_in_HBM[0], 0, max_input_size);
    memset(data_in_HBM[1], 0, max_input_size);

    async_readnorm(&aio_rf, (void *)(data_in_HBM[0]), nvmeFd, Data_lengths[0], Data_offsets[0]); 
    while( aio_error(&aio_rf) == EINPROGRESS ) {;}
    ret_aio = aio_return (&aio_rf);
    printf("Bytes Read. %d \n", ret_aio);

    CL_CHECK(cmd_.enqueueWriteBuffer(buffer_in_HBM[0], CL_FALSE, 0, max_input_size, data_in_HBM[0], nullptr, &kernel_events[0]));
    // CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_in_HBM[0])} , 0 , nullptr, &kernel_events[0]));  //DRAM FPGA
    kernel_wait_events.resize(0);
    CL_CHECK(cmd_.flush());
    CL_CHECK(cmd_.finish());
    std::cout << "C2F Done" << std::endl;

    kernelDD.setArg(0, buffer_in_HBM[0]);
    kernelDD.setArg(1, filConf_in_HBM[0]);
    kernelDD.setArg(2, buffer_out_HBM[0]);          //data0
    kernelDD.setArg(3, buffer_out_HBM[2]);          //data1
    kernelDD.setArg(4, buffer_out_HBM[4]);          //data2
    kernelDD.setArg(5, buffer_out_HBM[6]);          //data3
    kernelDD.setArg(6, buffer_out_HBM[8]);          //idx
    kernelDD.setArg(7, buffer_out_HBM[10]);         //data4(meta)
    kernelDD.setArg(8, sizeof(dCount), &dCount);

    CL_CHECK(cmd_.flush());
    CL_CHECK(cmd_.finish());
    std::cout << "Kernel Arg Set" << std::endl;

    kernel_wait_events.push_back(kernel_events[0]);
    CL_CHECK(cmd_.enqueueTask(kernelDD, &kernel_wait_events, &kernel_events[1]));
    kernel_wait_events.resize(0);
    kernel_wait_events.push_back(kernel_events[1]);
    CL_CHECK(cmd_.flush());
    CL_CHECK(cmd_.finish());
    std::cout << "Kernel Done" << std::endl;

    //CL_MIGRATE_MEM_OBJECT_CONTENT_UNDEFINED,  CL_MIGRATE_MEM_OBJECT_HOST     
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(filConf_in_HBM[0])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[0])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[2])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[4])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[6])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[8])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));
    CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[10])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                &kernel_wait_events, &kernel_events[2]));

    CL_CHECK(cmd_.flush());
    CL_CHECK(cmd_.finish());
    std::cout << "F2C Done" << std::endl;
    std::cout << "Initial Kernels Finished" << std::endl;

    int64_t load_time_ns = 0;
    int64_t compute_time_ns = 0; 
    int64_t store_time_ns = 0;
    double load_gbps = 0;
    double store_gbps = 0;

///////KERNEL Profiling////////
    cl_ulong start, end;
    kernel_events[0].getProfilingInfo(CL_PROFILING_COMMAND_START, &start);
    kernel_events[0].getProfilingInfo(CL_PROFILING_COMMAND_END, &end);
    load_time_ns = (end - start); //-- actual time is reported in nanoseconds

    kernel_events[1].getProfilingInfo(CL_PROFILING_COMMAND_START, &start);
    kernel_events[1].getProfilingInfo(CL_PROFILING_COMMAND_END, &end);
    compute_time_ns = (end - start); //-- actual time is reported in nanoseconds

    kernel_events[2].getProfilingInfo(CL_PROFILING_COMMAND_START, &start);
    kernel_events[2].getProfilingInfo(CL_PROFILING_COMMAND_END, &end);
    store_time_ns = (end - start); //-- actual time is reported in nanoseconds
    ///////Launching KERNEL DATAFLOW////////
        //non multiple RL adjustment
    uint32_t T_ITER = stripeCount*DATA_MUL;
    uint32_t NITERS = T_ITER + 4;  //IO, C2F, FCOMP, F2C, dCopy
            

    cl_uint one = 1;
    std::vector<cl::Event> C2F_events(NITERS);
    std::vector<cl::Event> FilC_events(4);
    std::vector<cl::Event> Comp_events(NITERS);
    std::vector<cl::Event> F2C_events(NITERS*7);
    std::vector<cl::Event> kernel_wait_events0;
    std::vector<cl::Event> kernel_wait_events1;

    auto asyncTimeS = std::chrono::steady_clock::now();
    auto asyncTimeE = std::chrono::steady_clock::now();
    auto asyncTime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);

    auto readTimeS = std::chrono::steady_clock::now();
    auto readTimeE = std::chrono::steady_clock::now();
    auto readTime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);

    auto FPGATimeS = std::chrono::steady_clock::now();
    auto FPGATimeE = std::chrono::steady_clock::now();
    auto FPGATime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);

    auto dCopyTimeS = std::chrono::steady_clock::now();
    auto dCopyTimeE = std::chrono::steady_clock::now();
    auto dCopyTime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);

    auto wrTimeS = std::chrono::steady_clock::now();
    auto wrTimeE = std::chrono::steady_clock::now();
    auto wrTime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);

    auto tempTimeA = std::chrono::steady_clock::now();
    auto tempTimeB = std::chrono::steady_clock::now();
    auto tempTime = std::chrono::duration_cast<std::chrono::microseconds>(tempTimeA - tempTimeB);

    double time_dCopy[NITERS] = {0.0F};
    double time_fpga[NITERS] = {0.0F};
    double time_C2F[NITERS] = {0.0F};
    double time_COMP[NITERS] = {0.0F};
    double time_F2C[NITERS] = {0.0F};
    double time_read[NITERS] = {0.0F};
    double time_wr[NITERS] = {0.0F};
    double time_async[NITERS] = {0.0F};
    // async_readnorm(void* data_in, int nvmeFd, int vector_size_bytes, int offset)
    // std::cout << "Starting DF, Total Iters: " << NITERS << std::endl;
    // std::cout << "stripeCount: " << stripeCount << std::endl;
    uint32_t offsetD = 0;
    uint32_t offsetT = 0;
    uint32_t dSize_prev = 0;
    uint32_t tTrackSize_prev = 0;
    std::thread t1, t2, t3, t4, t5; // Declare threads outside the if block

         if(true)
            {
                std::cout << "***Dataflow Implementation***" << std::endl;
                auto dfstart = std::chrono::steady_clock::now();
                for (int i = 0; i < NITERS; i++)
                {
                    // std::cout << "ITER COUNT: " << i << std::endl;
                    asyncTimeS = std::chrono::steady_clock::now();
                    //IO READ
                    if(i < T_ITER)
                    {
                        if((i%2) == 0)
                        {
                            async_readnorm(&aio_rf, (void *)(data_in_HBM[0]), nvmeFd, Data_length, Data_offset); 
                            // std::cout << "IO_E" << std::endl;
                        }
                        else
                        {
                            async_readnorm(&aio_rf1, (void *)(data_in_HBM[1]), nvmeFd, Data_length, Data_offset); 
                            // std::cout << "IO_O" << std::endl;
                        }
                    }   

                    // tempTimeA = std::chrono::steady_clock::now();
                    //CPU_2_FPGA
                    if((i >= 1) && (i < (T_ITER+1)))
                    {
                        // int Ssize = Data_lengths[i-1];
                        if(((i-1)%2) == 0)
                        {
                            CL_CHECK(cmd_.enqueueWriteBuffer(buffer_in_HBM[0], CL_FALSE, 0, Data_length, data_in_HBM[0], nullptr, &C2F_events[i-1]));
                            if(i==1)
                            {
                                CL_CHECK(cmd_.enqueueWriteBuffer(filConf_in_HBM[0], CL_FALSE, 0, 192, FilterConf_HBM[0], nullptr, &FilC_events[i-1]));
                            }
                            
                            // cmd_.enqueueMigrateMemObjects({(buffer_in_HBM[0])} , 0 , nullptr, &C2F_events[i-1]);
                            // std::cout << "C2F_E" << std::endl;
                        }
                        else
                        {
                            CL_CHECK(cmd_.enqueueWriteBuffer(buffer_in_HBM[1], CL_FALSE, 0, Data_length, data_in_HBM[1], nullptr, &C2F_events[i-1]));
                            if(i==2)
                            {
                                CL_CHECK(cmd_.enqueueWriteBuffer(filConf_in_HBM[1], CL_FALSE, 0, 192, FilterConf_HBM[1], nullptr, &FilC_events[i-1]));
                            }
                            // cmd_.enqueueMigrateMemObjects({(buffer_in_HBM[1])} , 0 , nullptr, &C2F_events[i-1]);
                            // std::cout << "C2F_O" << std::endl;
                        }
                        // std::cout << "C2F" << ":" << i-1 << std::endl;
                    }
                    // tempTimeB = std::chrono::steady_clock::now();
                    // tempTime = std::chrono::duration_cast<std::chrono::microseconds>(tempTimeB - tempTimeA);
                    // std::cout << "time_async C2F: " << static_cast<double>(tempTime.count()) <<std::endl;

                    //KERNEL CALL
                    if((i >= 2) && (i < (T_ITER+2)))
                    {
                        uint32_t dCount = Data_length / 64;
                        if ((Data_length % 64) != 0) {
                            dCount += 1;
                        }

                        if(((i-2)%2) == 0)
                        {
                            //Set Arg
                                kernelDD.setArg(0, buffer_in_HBM[0]);
                                kernelDD.setArg(1, filConf_in_HBM[0]);
                                kernelDD.setArg(2, buffer_out_HBM[0]);          //data0
                                kernelDD.setArg(3, buffer_out_HBM[2]);          //data1
                                kernelDD.setArg(4, buffer_out_HBM[4]);          //data2
                                kernelDD.setArg(5, buffer_out_HBM[6]);          //data3
                                kernelDD.setArg(6, buffer_out_HBM[8]);          //idx
                                kernelDD.setArg(7, buffer_out_HBM[10]);         //data4(meta)
                                kernelDD.setArg(8, sizeof(dCount), &dCount);
                            //Kernel Call
                                // std::cout << "COMP_E" << std::endl;
                        }
                        else
                        {
                            //Set Arg
                                kernelDD.setArg(0, buffer_in_HBM[1]);
                                kernelDD.setArg(1, filConf_in_HBM[1]);
                                kernelDD.setArg(2, buffer_out_HBM[1]);          //data0
                                kernelDD.setArg(3, buffer_out_HBM[3]);          //data1
                                kernelDD.setArg(4, buffer_out_HBM[5]);          //data2
                                kernelDD.setArg(5, buffer_out_HBM[7]);          //data3
                                kernelDD.setArg(6, buffer_out_HBM[9]);          //idx
                                kernelDD.setArg(7, buffer_out_HBM[11]);         //data4(meta)
                                kernelDD.setArg(8, sizeof(dCount), &dCount);
                            //Kernel Call
                                // std::cout << "COMP_O" << std::endl;
                        }
                        CL_CHECK(cmd_.enqueueTask(kernelDD, nullptr, &Comp_events[i-2]));
                        // std::cout << "COMP" << ":" << i-2 << std::endl;
                    }

                    //FPGA_2_CPU
                    if((i >= 3) && (i < (T_ITER+3)))
                    {
                        uint32_t dSizeBytes = 0;
                        uint32_t tTrack = 0;
                        if(((i-3)%2) == 0)
                        {
                            //read filtered Rows Val
                            CL_CHECK(cmd_.enqueueMigrateMemObjects({(filConf_in_HBM[0])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                            nullptr, &F2C_events[((i-3)*7)+6]));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+6])));

                            _512b* ptr = reinterpret_cast<ap_uint<512>*>(FilterConf_HBM[0]);
                            _512b tNum = ptr[2];
                            int filrows = tNum.range(31,0);
                            filterRowCount[i-3] = filrows;
                            uint32_t tRem = filrows%64;    //number not multiple of 64 numbers (16*4).
                            dSizeBytes = filrows/64;  //count of how many 512b(64bytes) to copy. 
                            if(tRem != 0)
                            {
                                dSizeBytes += 1;     //add one count.
                            }
                            dSizeBytes = dSizeBytes*64;       //each 512bit is 64bytes


                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[0])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[0]),
                                                            nullptr, &F2C_events[((i-3)*7)+0]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[2])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[2]),
                                                            nullptr, &F2C_events[((i-3)*7)+1]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[4])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[4]),
                                                            nullptr, &F2C_events[((i-3)*7)+2]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[6])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[6]),
                                                            nullptr, &F2C_events[((i-3)*7)+3]));
                            // CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[8])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                            //                                 nullptr, &F2C_events[((i-3)*7)+4]));
                            // CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[10])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                            //                                 nullptr, &F2C_events[((i-3)*7)+5]));
                            //read filtered Rows Val
                            // CL_CHECK(cmd_.enqueueMigrateMemObjects({(filConf_in_HBM[0])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                            //                                 nullptr, &F2C_events[((i-3)*7)+6]));
                            // std::cout << "F2C_E" << std::endl;
                        }
                        else
                        {
                            //read filtered Rows Val
                            CL_CHECK(cmd_.enqueueMigrateMemObjects({(filConf_in_HBM[1])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                                                            nullptr, &F2C_events[((i-3)*7)+6]));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+6])));


                            _512b* ptr = reinterpret_cast<ap_uint<512>*>(FilterConf_HBM[1]);
                            _512b tNum = ptr[2];
                            int filrows = tNum.range(31,0);
                            filterRowCount[i-3] = filrows;
                            uint32_t tRem = filrows%64;    //number not multiple of 64 numbers (16*4).
                            dSizeBytes = filrows/64;  //count of how many 512b(64bytes) to copy. 
                            if(tRem != 0)
                            {
                                dSizeBytes += 1;     //add one count.
                            }
                            dSizeBytes = dSizeBytes*64;       //each 512bit is 64bytes


                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[1])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[1]),
                                                            nullptr, &F2C_events[((i-3)*7)+0]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[3])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[3]),
                                                            nullptr, &F2C_events[((i-3)*7)+1]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[5])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[5]),
                                                            nullptr, &F2C_events[((i-3)*7)+2]));
                            CL_CHECK(cmd_.enqueueReadBuffer({(buffer_out_HBM[7])}, CL_FALSE , 0, dSizeBytes, (data_out_HBM[7]),
                                                            nullptr, &F2C_events[((i-3)*7)+3]));
                            // CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[9])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                            //                                 nullptr, &F2C_events[((i-3)*7)+4]));
                            // CL_CHECK(cmd_.enqueueMigrateMemObjects({(buffer_out_HBM[11])}, CL_MIGRATE_MEM_OBJECT_HOST , 
                            //                                 nullptr, &F2C_events[((i-3)*7)+5]));
                            
                            
                            // std::cout << "F2C_O" << std::endl;
                        }

                        // dSize_prev = dSizeBytes;
                        // tTrackSize_prev = tTrack;

                        // std::cout << "F2C" << ":" << i-3 << std::endl;
                    }

                    //Data Copy Calls
                    if((i >= 4) && (i < (T_ITER+4)))
                    {
                        uint32_t dSizeBytes = 0;
                        uint32_t tTrack = 0;
                        offsetD += dSize_prev;
                        offsetT += tTrackSize_prev;
                        int filrows = filterRowCount[i-4];
                        uint32_t tRem = filrows%64;    //number not multiple of 64 numbers (16*4).
                        dSizeBytes = filrows/64;  //count of how many 512b(64bytes) to copy. 
                        if(tRem != 0)
                        {
                            dSizeBytes += 1;     //add one count.
                        }
                        dSizeBytes = dSizeBytes*64;       //each 512bit is 64bytes
                        // tTrack = dSizeBytes*1.2;
                        // tRem = tTrack%16;  //128bit is 16bytes
                        // if(tRem != 0)
                        // {
                        //     tTrack += (16 - tRem);
                        // }
                        if (((i - 4) % 2) == 0) {                            
                            // Launch threads with offset handling
                            t1 = std::thread(copy_data, data_out_HBM[0], dataOut[0], dSizeBytes, offsetD);
                            t2 = std::thread(copy_data, data_out_HBM[2], dataOut[1], dSizeBytes, offsetD);
                            t3 = std::thread(copy_data, data_out_HBM[4], dataOut[2], dSizeBytes, offsetD);
                            t4 = std::thread(copy_data, data_out_HBM[6], dataOut[3], dSizeBytes, offsetD);
                            // t5 = std::thread(copy_data, data_out_HBM[10], trackOut, tTrack, offsetT);
                        } else {
                            // Launch threads with offset handling
                            t1 = std::thread(copy_data, data_out_HBM[1], dataOut[0], dSizeBytes, offsetD);
                            t2 = std::thread(copy_data, data_out_HBM[3], dataOut[1], dSizeBytes, offsetD);
                            t3 = std::thread(copy_data, data_out_HBM[5], dataOut[2], dSizeBytes, offsetD);
                            t4 = std::thread(copy_data, data_out_HBM[7], dataOut[3], dSizeBytes, offsetD);
                            // t5 = std::thread(copy_data, data_out_HBM[11], trackOut, tTrack, offsetT);
                        }

                        dSize_prev = dSizeBytes;
                        tTrackSize_prev = tTrack;

                        // std::cout << "D_COPY" << ":" << i-4 << std::endl;
                    }
                    asyncTimeE = std::chrono::steady_clock::now();
                    asyncTime = std::chrono::duration_cast<std::chrono::microseconds>(asyncTimeE - asyncTimeS);
                    time_async[i] = static_cast<double>(asyncTime.count());

                    ///WAITS///
                    
                    //IO READ
                    readTimeS = std::chrono::steady_clock::now();
                    if(i < T_ITER)
                    {
                        int ret = 0;
                        if(i%2 == 0)
                        {
                            while( aio_error(&aio_rf) == EINPROGRESS ) {;}
                            ret = aio_return (&aio_rf);
                            if(ret <= 0)
                            {
                                std::cerr << "Read Error. Bytes Read: " << ret << std::endl;
                            }
                            //readTimeE = std::chrono::steady_clock::now();
                            //wrTimeS = std::chrono::steady_clock::now();
                            //writeZeros(data_in_HBM[0], Data_lengths[i], PIPELINE_DEPTH);
                        }
                        else
                        {
                            while( aio_error(&aio_rf1) == EINPROGRESS ) {;}
                            ret = aio_return (&aio_rf1);
                            if(ret <= 0)
                            {
                                std::cerr << "Read Error. Bytes Read: " << ret << std::endl;
                            }
                            //readTimeE = std::chrono::steady_clock::now();
                            //wrTimeS = std::chrono::steady_clock::now();
                            //writeZeros(data_in_HBM[1], Data_lengths[i], PIPELINE_DEPTH);
                        }
                        // std::cout << "Read Bytes: " << ret << std::endl;
                    }   
                    // readTimeE = std::chrono::steady_clock::now();
                    wrTimeE = std::chrono::steady_clock::now();
                    wrTime = std::chrono::duration_cast<std::chrono::microseconds>(wrTimeE - wrTimeS);
                    readTime = std::chrono::duration_cast<std::chrono::microseconds>(readTimeE - readTimeS);
                    time_read[i] = static_cast<double>(readTime.count());
                    time_wr[i] = static_cast<double>(wrTime.count());

                    C2FTimeS = std::chrono::steady_clock::now();
                    //CPU_2_FPGA
                    if((i >= 1) && (i < (T_ITER+1)))
                    {
                        CL_CHECK(clWaitForEvents(one,(cl_event*)(&C2F_events[i-1])));
                        if(i < 3)
                        {
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&FilC_events[i-1])));
                        }
                        // std::cout << "C2F Wait" << ":" << i-1 << std::endl;
                    }                    
                    C2FTimeE = std::chrono::steady_clock::now();
                    // C2FTime = std::chrono::duration_cast<std::chrono::microseconds>(C2FTimeE - C2FTimeS);
                    // time_C2F[i] = static_cast<double>(C2FTime.count());
                    
                    COMPTimeS = std::chrono::steady_clock::now();
                    //KERNEL CALL
                    if((i >= 2) && (i < (T_ITER+2)))
                    {
                        CL_CHECK(clWaitForEvents(one,(cl_event*)(&Comp_events[i-2])));
                        // std::cout << "COMP Wait" <<":" << i-2 << std::endl;
                    }
                    COMPTimeE = std::chrono::steady_clock::now();
                    // COMPTime = std::chrono::duration_cast<std::chrono::microseconds>(COMPTimeE - COMPTimeS);
                    // time_COMP[i] = static_cast<double>(COMPTime.count());

                    F2CTimeS = std::chrono::steady_clock::now();
                    //FPGA_2_CPU
                    if((i >= 3) && (i < (T_ITER+3)))
                    {
                        if(((i-3)%2) == 0)
                        {
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+0])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+1])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+2])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+3])));
                            // CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+4])));        //data idx
                            // CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+5])));        //meta
                        }
                        else
                        {
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+0])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+1])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+2])));
                            CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+3])));
                            // CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+4])));        //data idx
                            // CL_CHECK(clWaitForEvents(one,(cl_event*)(&F2C_events[((i-3)*7)+5])));        //meta
                        }
                        // std::cout << "F2C Wait" << ":" << i-3 << std::endl;
                    }
                    // FPGATimeE = std::chrono::steady_clock::now();
                    // FPGATime = std::chrono::duration_cast<std::chrono::microseconds>(FPGATimeE - FPGATimeS);
                    // time_fpga[i] = static_cast<double>(FPGATime.count());
                    F2CTimeE = std::chrono::steady_clock::now();
                    // F2CTime = std::chrono::duration_cast<std::chrono::microseconds>(F2CTimeE - F2CTimeS);
                    // time_F2C[i] = static_cast<double>(F2CTime.count());


                    //dataCopy
                    dCopyTimeS = std::chrono::steady_clock::now();
                    if((i >= 4) && (i < (T_ITER+4)))
                    {
                        // Wait for all threads to finish
                        t1.join();
                        t2.join();
                        t3.join();
                        t4.join();
                        // t5.join();
                        // std::cout << "D_COPY wait" << ":" << i-4 << std::endl;
                    }
                    dCopyTimeE = std::chrono::steady_clock::now();
           
                }
            }

    iter_ = std::make_unique<FPGARecordBatchIterator>(dataOut[0], dataOut[1], dataOut[2], dataOut[3], filterRowCount, T_ITER, stripe_rows.data(), stripeCount, 4096);
  }


  std::shared_ptr<gluten::ColumnarBatch> next() override {
    auto maybe_batch = iter_->Next();
    if (!maybe_batch.ok() || !maybe_batch.ValueOrDie()) {
      return nullptr;
    }
    std::shared_ptr<arrow::RecordBatch> batch = maybe_batch.ValueOrDie();
    DLOG(INFO) << "OrcFPGAIterator get a batch, num rows: " << (batch ? batch->num_rows() : 0);

    return convertBatch(std::make_shared<gluten::ArrowColumnarBatch>(batch));
  }
  private:
    std::unique_ptr<FPGARecordBatchIterator> iter_;
};

class OrcStreamReaderIterator final : public OrcReaderIterator {
 public:
  explicit OrcStreamReaderIterator(const std::string& path) : OrcReaderIterator(path) {
    createReader();
  }

  std::shared_ptr<gluten::ColumnarBatch> next() override {
    auto startTime = std::chrono::steady_clock::now();
    GLUTEN_ASSIGN_OR_THROW(auto batch, recordBatchReader_->Next());
    DLOG(INFO) << "OrcStreamReaderIterator get a batch, num rows: " << (batch ? batch->num_rows() : 0);
    collectBatchTime_ +=
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - startTime).count();
    if (batch == nullptr) {
      return nullptr;
    }
    return convertBatch(std::make_shared<gluten::ArrowColumnarBatch>(batch));
  }
};

class OrcBufferedReaderIterator final : public OrcReaderIterator {
 public:
  explicit OrcBufferedReaderIterator(const std::string& path) : OrcReaderIterator(path) {
    createReader();
    collectBatches();
    iter_ = batches_.begin();
    DLOG(INFO) << "OrcBufferedReaderIterator open file: " << path;
    DLOG(INFO) << "Number of input batches: " << std::to_string(batches_.size());
    if (iter_ != batches_.cend()) {
      DLOG(INFO) << "columns: " << (*iter_)->num_columns();
      DLOG(INFO) << "rows: " << (*iter_)->num_rows();
    }
  }

  std::shared_ptr<gluten::ColumnarBatch> next() override {
    if (iter_ == batches_.cend()) {
      return nullptr;
    }
    return convertBatch(std::make_shared<gluten::ArrowColumnarBatch>(*iter_++));
  }

 private:
  void collectBatches() {
    auto startTime = std::chrono::steady_clock::now();
    GLUTEN_ASSIGN_OR_THROW(batches_, recordBatchReader_->ToRecordBatches());
    auto endTime = std::chrono::steady_clock::now();
    collectBatchTime_ += std::chrono::duration_cast<std::chrono::nanoseconds>(endTime - startTime).count();
  }

  arrow::RecordBatchVector batches_;
  std::vector<std::shared_ptr<arrow::RecordBatch>>::const_iterator iter_;
};

} // namespace gluten